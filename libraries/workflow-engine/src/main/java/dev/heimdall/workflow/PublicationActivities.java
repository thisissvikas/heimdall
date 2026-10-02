package dev.heimdall.workflow;

import dev.heimdall.contracts.Execution.*;
import io.temporal.activity.*;

@ActivityInterface
public interface PublicationActivities {
  @ActivityMethod
  void publish(ResultEvent event);
}
