export default {
  counterMetric: wolpi.metrics.counter(
      "metrics_test_counter", "foos", "A counter for testing metrics",
      {"label_a": "value_a", "label_b": "value_b"}),
  gaugeMetric: wolpi.metrics.gauge(
      "metrics_test_gauge", "foos", "A gauge for testing metrics",
      {"label_c": "value_c", "label_d": "value_d"}),
  timerMetric: wolpi.metrics.timer(
      "metrics_test_timer", "A timer for testing metrics",
      {"label_e": "value_e", "label_f": "value_f"}),

  info() {
    return {
      apiVersion: 1,
      name: "Metrics Test Extension",
      description: "Just some tests that metrics work"
    };
  },

  cleanup() {
    // NOP
  },

  authorize(identifier, headers, clientIp) {
    if (identifier === "increment-four") {
      this.counterMetric.increment(4);
    } else {
      this.counterMetric.increment();
    }
    if (identifier.startsWith("gauge-")) {
      const parts = identifier.split("-");
      const value = parseFloat(parts[1]);
      this.gaugeMetric.set(value);
    }
    if (identifier.startsWith("timed-")) {
      const Thread = Java.type("java.lang.Thread");
      const parts = identifier.split("-");
      const milliSeconds = parseInt(parts[1], 10);
      const recorded = this.timerMetric.record(() => {
        // Simulate some processing time
        Thread.sleep(milliSeconds);
        return milliSeconds;
      });
      if (recorded !== milliSeconds) {
        throw new Error(`Expected timer.record() to return ${milliSeconds}, got ${recorded}`);
      }
    }
    return true;
  },

  resolve(identifier, eTag, lastModified) {
    const Thread = Java.type("java.lang.Thread");
    const timerContext = this.timerMetric.start();
    try {
      // Simulate some processing time
      Thread.sleep(3000);
    } finally {
      timerContext.stop();
    }
  }
}