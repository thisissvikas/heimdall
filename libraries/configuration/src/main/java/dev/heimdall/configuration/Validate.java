package dev.heimdall.configuration;

import java.nio.file.Path;

public final class Validate {
  public static void main(String[] args) throws Exception {
    var snapshot = new Compiler().compile(Path.of(args[0]), "validation");
    System.out.println(
        "Validated %s API monitors, %s locations; bundle %s"
            .formatted(snapshot.monitors().size(), snapshot.locations().size(), snapshot.digest()));
  }
}
