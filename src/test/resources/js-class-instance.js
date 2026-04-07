class ClassInstanceExtension {
  #authorized = false;

  constructor() {
    this.log = wolpi.logger.getLogger("class-instance");
    this.constructorSawLogger = typeof this.log.info === "function";
  }

  info() {
    return {
      apiVersion: 1,
      name: "Class Instance JS Extension",
      description: "A JavaScript extension exported as a class instance"
    };
  }

  setup() {
    this.log.info("setup");
  }

  authorize(identifier) {
    if (identifier === "class-instance-authorize") {
      this.#authorized = true;
    }
    return this.constructorSawLogger;
  }

  resolve(identifier) {
    if (identifier !== "class-instance-resolve" || !this.#authorized) {
      return null;
    }
    return {
      path: "/tmp/images/class-instance.jp2"
    };
  }

  cleanup() {
    this.#authorized = false;
  }
}

export default new ClassInstanceExtension();
