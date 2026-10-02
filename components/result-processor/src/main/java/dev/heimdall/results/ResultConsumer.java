package dev.heimdall.results;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Execution.ResultEvent;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;

public final class ResultConsumer implements Runnable, AutoCloseable {
  private final KafkaConsumer<String, String> consumer;
  private final JdbcResults results;
  private volatile boolean stopped;

  public ResultConsumer(String bootstrap, String group, JdbcResults results) {
    this.results = results;
    var props = new Properties();
    props.put("bootstrap.servers", bootstrap);
    props.put("group.id", group);
    props.put("enable.auto.commit", "false");
    props.put("auto.offset.reset", "earliest");
    props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    consumer = new KafkaConsumer<>(props);
  }

  @Override
  public void run() {
    try {
      consumer.subscribe(List.of("heimdall-results"));
      while (!stopped) {
        var batch = consumer.poll(Duration.ofSeconds(1));
        for (var record : batch) results.ingest(Json.read(record.value(), ResultEvent.class));
        consumer.commitSync();
      }
    } catch (WakeupException e) {
      if (!stopped) throw e;
    } finally {
      consumer.close();
    }
  }

  @Override
  public void close() {
    stopped = true;
    consumer.wakeup();
  }
}
