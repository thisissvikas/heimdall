package dev.heimdall.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContractsTest {
  @Test
  void durationsAndStableIdentifiers() {
    assertEquals(Duration.ofMinutes(25), Definitions.duration("25m"));
    assertEquals(Duration.ofMillis(25), Definitions.duration("25ms"));
    assertThrows(IllegalArgumentException.class, () -> Definitions.duration("-2s"));
    var id = ExecutionIds.workflowId("run", "team/app/service/test", "eu-west");
    assertEquals(id, ExecutionIds.workflowId("run", "team/app/service/test", "eu-west"));
    assertNotEquals(id, ExecutionIds.workflowId("run", "team/app/service/test", "us-east"));
    assertTrue(id.startsWith("api-run-"));
  }

  @Test
  void rejectsUnknownFieldsAndTrailingDocuments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Json.read("{\"yaml\":\"arbitrary\"}", Execution.StartRun.class));
    assertThrows(
        IllegalArgumentException.class, () -> Json.read("{} {}", Execution.StartRun.class));
  }

  @Test
  void requestCollectionsCannotBeMutated() {
    var params = new HashMap<String, Object>();
    params.put("name", "before");
    var run = new Execution.StartRun(List.of("test"), "staging", List.of("local"), params, null);
    params.put("name", "after");
    assertEquals("before", run.parameters().get("name"));
    assertThrows(
        UnsupportedOperationException.class, () -> run.parameters().put("name", "changed"));
  }
}
