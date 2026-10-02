package dev.heimdall.results;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Execution.*;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;

public final class ResultProcessor {
  public static void main(String[] args) {
    var ds = Database.connect();
    Database.migrate(ds);
    var results = new JdbcResults(ds);
    var p = new Properties();
    p.put("bootstrap.servers", System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092"));
    p.put("group.id", "heimdall-results-v1");
    p.put("enable.auto.commit", "false");
    p.put("auto.offset.reset", "earliest");
    p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    try (var consumer = new KafkaConsumer<String, String>(p)) {
      consumer.subscribe(List.of("heimdall-results"));
      while (!Thread.currentThread().isInterrupted()) {
        var records = consumer.poll(Duration.ofSeconds(1));
        for (var record : records) results.ingest(Json.read(record.value(), ResultEvent.class));
        consumer.commitSync();
      }
    } finally {
      ds.close();
    }
  }
}
