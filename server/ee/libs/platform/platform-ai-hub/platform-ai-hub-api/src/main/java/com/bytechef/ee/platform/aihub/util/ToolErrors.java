/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.util;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared helper for serialising tool-call error responses to JSON. Replaces the inline
 *
 * <pre>{@code
 * try {
 *     return jsonMapper.writeValueAsString(Map.of("error", message));
 * } catch (JacksonException e) {
 *     return "{\"error\":\"serialization failure\"}";
 * }
 * }</pre>
 *
 * pattern that was duplicated across every tool callback. Logs at ERROR if the fallback path is hit so the failure does
 * not vanish silently.
 *
 * <p>
 * All new tool callbacks should use {@link #toolError(JsonMapper, String)}; existing callbacks with their own private
 * {@code toolError} helper should be migrated incrementally to use this one.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class ToolErrors {

    private static final Logger log = LoggerFactory.getLogger(ToolErrors.class);

    private ToolErrors() {
    }

    /**
     * Serialises a {@code {"error": <message>}} JSON object using the supplied {@link JsonMapper}. If serialisation
     * fails (which is essentially impossible for {@code Map.of("error", String)} but kept for type-safety), logs the
     * failure at ERROR with the underlying exception and returns a constant fallback string.
     *
     * @param jsonMapper the configured Jackson mapper
     * @param message    the human-readable error message
     * @return a JSON string of the form {@code {"error":"<message>"}} or the fallback {@code {"error":"serialization
     *         failure"}}
     */
    public static String toolError(JsonMapper jsonMapper, String message) {
        try {
            return jsonMapper.writeValueAsString(Map.of("error", message));
        } catch (JacksonException exception) {
            // The "impossible" branch — Map.of("error", String) is always serialisable. If it ever fires, the
            // JsonMapper itself is broken (custom modules misconfigured, etc.) and ops needs to know.
            log.error("Failed to serialise tool error response for message '{}': {}",
                message, exception.toString(), exception);

            return "{\"error\":\"serialization failure\"}";
        }
    }

    /**
     * Logs a tool-callback {@link RuntimeException} at WARN with sanitised exception text and returns a typed tool
     * error string the agent loop can recover from. Use as the {@code catch (RuntimeException)} arm of every
     * {@code call(toolInput, toolContext)} method so a transient DB outage, NPE, or 4xx/5xx from a downstream service
     * does not abort the entire agent run.
     *
     * <p>
     * The returned string intentionally surfaces only the exception's simple class name — never
     * {@link RuntimeException#getMessage()}. Underlying messages from JDBC, Spring AI, HttpClient, etc. routinely
     * contain JDBC URLs, table/column names, full SQL statements, and stack-trace fragments. The LLM/chat transcript is
     * persisted into the task row and surfaced verbatim to end users; leaking those internals contradicts the
     * documented hardening contract on {@code CreateBinaryAssetFileToolCallback} (see its
     * {@code formatRuntimeFailureMessage}). The simple class name is enough for the LLM to recover with a structured
     * apology; ops gets the full sanitised text from the WARN log + stack.
     * </p>
     *
     * @param jsonMapper  the Jackson mapper used to serialise the response
     * @param sourceClass the calling tool callback's class — used to resolve the SLF4J log so the WARN line is
     *                    attributed to the right component instead of {@code ToolErrors}
     * @param toolName    human-readable tool name surfaced to the LLM in the error payload
     * @param exception   the runtime exception that escaped the callback's main try block
     * @return a JSON string of the form {@code {"error":"<toolName> failed (<simpleName>)"}}
     */
    public static String runtimeFailure(
        JsonMapper jsonMapper, Class<?> sourceClass, String toolName, RuntimeException exception) {

        Logger sourceLogger = LoggerFactory.getLogger(sourceClass);

        sourceLogger.warn("{} failed: {}", toolName,
            LogSanitizer.sanitizeForLog(exception.toString()), exception);

        return toolError(
            jsonMapper,
            toolName + " failed (" + exception.getClass()
                .getSimpleName() + ")");
    }
}
