package dev.mdz.iiif.wolpi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("WolpiApplication")
class WolpiApplicationTests {

  @Test
  @DisplayName("should load the application context")
  void contextLoads() {
    // Just a very basic integration test to ensure that the autowiring works.
  }
}
