package com.hobbytree.hobbytree;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Issue #276 is this app's first @Scheduled job (AccountAnonymizationScheduler).
@EnableScheduling
@SpringBootApplication
public class HobbytreeApplication {

  public static void main(String[] args) {
    SpringApplication.run(HobbytreeApplication.class, args);
  }
}
