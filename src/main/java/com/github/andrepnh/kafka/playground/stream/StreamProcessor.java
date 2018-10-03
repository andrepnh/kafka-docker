package com.github.andrepnh.kafka.playground.stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.andrepnh.kafka.playground.db.gen.StockQuantity;
import com.github.andrepnh.kafka.playground.db.gen.Warehouse;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Serialized;

public class StreamProcessor {
  public static void main(String[] args) {
    var processor = new StreamProcessor();
    var topology = processor.buildTopology();
    var properties = StreamProperties.newDefaultStreamProperties(UUID.randomUUID().toString());
    System.out.println(topology.describe());
    var streams = new KafkaStreams(topology, properties);
    streams.cleanUp();
    streams.setUncaughtExceptionHandler((thread, throwable) -> {
      throwable.printStackTrace();
      streams.close();
      System.exit(1);
    });
    streams.start();
    Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
  }

  public Topology buildTopology() {
    var builder = new StreamsBuilder();
    KStream<List<Integer>, StockQuantity> stockStream = builder
        .<JsonNode, JsonNode>stream("connect_test.public.stockquantity")
        .map(this::stripStockQuantityMetadata)
        .map(this::deserializeStockQuantity);
    KTable<List<Integer>, Quantity> warehouseStock =
        stockStream
            .groupByKey(
                Serialized.with(
                    JsonSerde.of(new TypeReference<>() {}), JsonSerde.of(StockQuantity.class)))
            .aggregate(
                () -> Quantity.empty(LocalDateTime.MIN.atZone(ZoneOffset.UTC)),
                this::lastWriteWins,
                Materialized.with(
                    JsonSerde.of(new TypeReference<>() {}), JsonSerde.of(Quantity.class)));
    var warehouseStockStream = warehouseStock.toStream();
    warehouseStockStream.to(
        "warehouse-stock",
        Produced.with(
            JsonSerde.of(new TypeReference<>() { }),
            JsonSerde.of(Quantity.class)));

    KStream<Integer, Warehouse> warehouseStream = builder
        .<JsonNode, JsonNode>stream("connect_test.public.warehouse")
        .map(this::stripWarehouseMetadata)
        .map(this::deserializeWarehouse);
    KTable<Integer, Quantity> warehouseQuantityTable = warehouseStock
        .groupBy((key, value) -> new KeyValue<>(key.get(0), value),
            Serialized.with(Serdes.Integer(), JsonSerde.of(Quantity.class)))
        .reduce(Quantity::sum, Quantity::subtract);
    KStream<WarehouseKey, Allocation> warehouseCapacity = warehouseStream
        .join(warehouseQuantityTable,
            WarehouseStockQuantity::new,
            Joined.with(Serdes.Integer(),
                JsonSerde.of(Warehouse.class),
                JsonSerde.of(Quantity.class)))
        .map((id, warehouseStockQuantity) -> {
          var warehouse = warehouseStockQuantity.getWarehouse();
          var qty = warehouseStockQuantity.getStockQuantity();
          var key = new WarehouseKey(id,
              warehouse.getName(),
              warehouse.getLatitude(),
              warehouse.getLongitude());
          var allocation = Allocation.calculate(qty.getQuantity(), warehouse.getStorageCapacity());
          return new KeyValue<>(key, allocation);
        });
    warehouseCapacity.to("warehouse-capacity",
        Produced.with(JsonSerde.of(WarehouseKey.class), JsonSerde.of(Allocation.class)));


    KTable<Integer, GlobalStockQuantity> metaGlobalStock =
        warehouseStock
            .groupBy(
                (warehouseItemPair, qty) -> new KeyValue<>(warehouseItemPair.get(1), qty),
                Serialized.with(Serdes.Integer(), JsonSerde.of(Quantity.class)))
            .aggregate(
                GlobalStockQuantity::zero,
                (key, value, acc) -> acc.add(value.getQuantity()),
                (key, value, acc) -> acc.subtract(value.getQuantity()),
                Materialized.with(Serdes.Integer(), JsonSerde.of(GlobalStockQuantity.class)));
    var metaGlobalStockStream = metaGlobalStock.toStream();
    KStream<Integer, Integer> globalStockStream = metaGlobalStockStream
        .mapValues(GlobalStockQuantity::getQuantity);
    globalStockStream.to("global-stock", Produced.with(Serdes.Integer(), Serdes.Integer()));

    KTable<Integer, List<Integer>> globalStockMinMax = metaGlobalStockStream
        .groupByKey()
        .aggregate(() -> Lists.newArrayList(Integer.MAX_VALUE, Integer.MIN_VALUE),
            (key, value, acc) -> {
              int min = acc.get(0), max = acc.get(1);
              if (value.isSubtractorUpdate()) {
                // We ignore updates coming from subtractors because they can drop min to 0.
                // Suppose the min is currently 200 and global is updated to 100. That will trigger
                // its subtractor dropping the value to 0 and the actual update won't be reflected.
                return Lists.newArrayList(min, max);
              }
              if (value.getQuantity() < min) {
                min = value.getQuantity();
              }
              if (value.getQuantity() > max) {
                max = value.getQuantity();
              }
              return Lists.newArrayList(min, max);
            }, Materialized.with(Serdes.Integer(), JsonSerde.of(new TypeReference<>() { })));
    KStream<Integer, Double> globalStockPercentagePerItem = metaGlobalStockStream
        .join(globalStockMinMax, (global, minMax) -> {
          int min = minMax.get(0), max = minMax.get(1);
          int offset = global.getQuantity() - min, maxOffset = max - min;
          return (double) offset / maxOffset;
        }, Joined.with(Serdes.Integer(),
            JsonSerde.of(GlobalStockQuantity.class),
            JsonSerde.of(new TypeReference<>() { })));
    globalStockPercentagePerItem.to("global-stock-percentage",
        Produced.with(Serdes.Integer(), Serdes.Double()));

    return builder.build();
  }

  private KeyValue<JsonNode, JsonNode> stripWarehouseMetadata(JsonNode key, JsonNode value) {
    var id = JsonNodeFactory.instance.numberNode(key.at("/payload/id").intValue());
    var currentState = value.at("/payload/after");
    return new KeyValue<>(id, currentState);
  }

  private KeyValue<List<Integer>, StockQuantity> deserializeStockQuantity(JsonNode idsArray, JsonNode value) {
    var ids = SerializationUtils.deserialize(idsArray, new TypeReference<List<Integer>>() { });
    var dbStockQuantity = SerializationUtils.deserialize(value, DbStockQuantity.class);
    return new KeyValue<>(ids, dbStockQuantity.toStockQuantity());
  }

  private KeyValue<Integer, Warehouse> deserializeWarehouse(JsonNode jsonId, JsonNode jsonWarehouse) {
    int id = jsonId.asInt();
    var dbWarehouse = SerializationUtils.deserialize(jsonWarehouse, DbWarehouse.class);
    return new KeyValue<>(id, dbWarehouse.toWarehouse());
  }

  private Quantity lastWriteWins(List<Integer> ids, StockQuantity state, Quantity acc) {
    if (acc.getLastUpdate().isAfter(state.getLastUpdate())) {
      return acc;
    } else {
      return Quantity.of(state);
    }
  }

  private Quantity lastWriteWins(Quantity stockQuantity1, Quantity quantity) {
    if (stockQuantity1.getLastUpdate().isAfter(quantity.getLastUpdate())) {
      return stockQuantity1;
    } else {
      return quantity;
    }
  }

  private KeyValue<JsonNode, JsonNode> stripStockQuantityMetadata(JsonNode key, JsonNode value) {
    var currentState = value.at("/payload/after");
    ArrayNode ids = JsonNodeFactory.instance.arrayNode(2);
    ids.add(key.at("/payload/warehouseid"));
    ids.add(key.at("/payload/stockitemid"));
    return new KeyValue<>(ids, currentState);
  }

  private static class GlobalStockQuantity {
    private final int quantity;

    private final boolean subtractorUpdate;

    public GlobalStockQuantity(int quantity, boolean subtractorUpdate) {
      this.quantity = quantity;
      this.subtractorUpdate = subtractorUpdate;
    }

    public static GlobalStockQuantity zero() {
      return new GlobalStockQuantity(0, false);
    }

    public GlobalStockQuantity add(int quantity) {
      return new GlobalStockQuantity(this.quantity + quantity, false);
    }

    public GlobalStockQuantity subtract(int quantity) {
      return new GlobalStockQuantity(this.quantity - quantity, true);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("quantity", quantity)
          .add("subtractorUpdate", subtractorUpdate)
          .toString();
    }

    public int getQuantity() {
      return quantity;
    }

    /**
     * @return true if this quantity comes from a subtractor used to aggregate a KTable.
     */
    public boolean isSubtractorUpdate() {
      return subtractorUpdate;
    }
  }

  private static class WarehouseStockQuantity {
    private final Warehouse warehouse;

    private final Quantity stockQuantity;

    public WarehouseStockQuantity(Warehouse warehouse,
        Quantity stockQuantity) {
      this.warehouse = warehouse;
      this.stockQuantity = stockQuantity;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      WarehouseStockQuantity that = (WarehouseStockQuantity) o;
      return Objects.equals(warehouse, that.warehouse) &&
          Objects.equals(stockQuantity, that.stockQuantity);
    }

    @Override
    public int hashCode() {
      return Objects.hash(warehouse, stockQuantity);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("warehouse", warehouse)
          .add("stockQuantity", stockQuantity)
          .toString();
    }

    public Warehouse getWarehouse() {
      return warehouse;
    }

    public Quantity getStockQuantity() {
      return stockQuantity;
    }
  }
}
