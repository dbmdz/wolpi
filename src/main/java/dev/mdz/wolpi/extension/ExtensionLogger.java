package dev.mdz.wolpi.extension;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;

/// A logger for extension code that prefixes loggers with "dev.mdz.wolpi.extension.<extension-name>".
///
/// Additional context details can be provided as key-value pairs in a map for each log level.
///
/// Allows creating sub-loggers for different components within an extension.
public class ExtensionLogger {
    private final Logger rootLogger;
    private final String name;

    public ExtensionLogger(String name) {
        this.name = name;
        this.rootLogger = LoggerFactory.getLogger("dev.mdz.wolpi.extension." + name);
    }

    /// Creates a sub-logger with the given name appended to the current logger's name.
    public ExtensionLogger getLogger(String name) {
        return new ExtensionLogger("%s.%s".formatted(this.name, name));
    }

    private void logWithDetails(String message, Map<String, Object> details, LogLevel level) {
        var builder =
                switch (level) {
                    case DEBUG -> rootLogger.atDebug();
                    case INFO -> rootLogger.atInfo();
                    case WARN -> rootLogger.atWarn();
                    case ERROR -> rootLogger.atError();
                    case TRACE, OFF, FATAL -> null;
                };
        if (builder == null) {
            return;
        }
        details.forEach(builder::addKeyValue);
        builder.log(message);
    }

    /// Logs a message at the DEBUG level.
    public void debug(String message) {
        rootLogger.debug(message);
    }

    /// Logs a message at the DEBUG level with additional context details.
    public void debug(String message, Map<String, Object> details) {
        logWithDetails(message, details, LogLevel.DEBUG);
    }

    /// Logs a message at the INFO level.
    public void info(String message) {
        rootLogger.info(message);
    }

    /// Logs a message at the INFO level with additional context details.
    public void info(String message, Map<String, Object> details) {
        logWithDetails(message, details, LogLevel.INFO);
    }

    /// Logs a message at the WARN level.
    public void warn(String message) {
        rootLogger.warn(message);
    }

    /// Logs a message at the WARN level with additional context details.
    public void warn(String message, Map<String, Object> details) {
        logWithDetails(message, details, LogLevel.WARN);
    }

    /// Logs a message at the ERROR level.
    public void error(String message) {
        rootLogger.error(message);
    }

    /// Logs a message at the ERROR level with additional context details.
    public void error(String message, Map<String, Object> details) {
        logWithDetails(message, details, LogLevel.ERROR);
    }
}
