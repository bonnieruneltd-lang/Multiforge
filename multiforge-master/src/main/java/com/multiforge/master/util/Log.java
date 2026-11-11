package com.multiforge.master.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central logging facade for the MultiForge master process.
 *
 * <p>The original MultiPaper master wrote diagnostics with {@code System.out}.
 * MultiForge keeps a single logger so that operators can configure levels,
 * rotate logs and correlate master output with the Forge servers' logs.</p>
 *
 * <p>All methods are static and thread-safe; log4j2 appenders perform their own
 * synchronization. Callers must not rely on ordering between threads.</p>
 */
public final class Log {

    private static final Logger LOGGER = LogManager.getLogger("MultiForgeMaster");

    private Log() {
    }

    /** Logs an informational message. */
    public static void info(String message) {
        LOGGER.info(message);
    }

    /** Logs an informational message together with a throwable stack trace. */
    public static void info(String message, Throwable throwable) {
        LOGGER.info(message, throwable);
    }

    /** Logs a warning that an operator should look at. */
    public static void warn(String message) {
        LOGGER.warn(message);
    }

    /** Logs a warning together with a throwable stack trace. */
    public static void warn(String message, Throwable throwable) {
        LOGGER.warn(message, throwable);
    }

    /** Logs an error. Reserved for failures that degrade cluster behaviour. */
    public static void error(String message) {
        LOGGER.error(message);
    }

    /** Logs an error together with a throwable stack trace. */
    public static void error(String message, Throwable throwable) {
        LOGGER.error(message, throwable);
    }

    /**
     * @return {@code true} when debug logging is enabled for the master logger;
     *         lets callers skip building expensive diagnostic strings
     */
    public static boolean isDebugEnabled() {
        return LOGGER.isDebugEnabled();
    }
}
