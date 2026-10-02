package dev.heimdall.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ControlPlane {
  public static void main(String[] args) {
    SpringApplication.run(ControlPlane.class, args);
  }
}
