module io.github.throttle {
    // Public API — clients use these to submit tasks and configure the executor
    exports io.github.throttle.service.api;
    exports io.github.throttle.service.base;
    exports io.github.throttle.service.config;
    exports io.github.throttle.service.factory;
    exports io.github.throttle.service.monitor;

    // Internal implementation — not exported
    // io.github.throttle.service.core  (internal)
    // io.github.throttle.service.examples (internal)

    requires org.slf4j;
    requires java.management;
}

