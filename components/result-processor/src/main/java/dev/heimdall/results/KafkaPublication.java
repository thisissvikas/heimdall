package dev.heimdall.results;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.workflow.PublicationActivities;
import java.util.*;
import org.apache.kafka.clients.producer.*;

public final class KafkaPublication implements PublicationActivities, AutoCloseable {
  private final KafkaProducer<String, String> producer;

  public KafkaPublication(String bootstrap) {
    var p = new Properties();
    p.put("bootstrap.servers", bootstrap);
    p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    p.put("acks", "all");
    p.put("enable.idempotence", "true");
    p.put("delivery.timeout.ms", "15000");
    p.put("request.timeout.ms", "10000");
    producer = new KafkaProducer<>(p);
  }

  @Override
  public void publish(ResultEvent event) {
    try {
      producer
          .send(new ProducerRecord<>("heimdall-results", event.runId(), Json.write(event)))
          .get(20, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("Result publication failed", e);
    }
  }

  @Override
  public void close() {
    producer.close();
  }
}
