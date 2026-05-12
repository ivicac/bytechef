/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.tool;

import com.bytechef.ee.platform.aihub.util.LogSanitizer;
import com.bytechef.ee.platform.aihub.util.ToolErrors;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signaling-only Spring AI {@link ToolCallback} that instructs the AI Hub client to open a "Connect &lt;Component&gt;"
 * dialog inline in the chat thread. The server returns a marker payload with {@code kind: "create-connection"} that the
 * client subscriber recognises and renders as a button. No connection is created server-side; the client drives OAuth,
 * credential entry, and persistence through the existing {@code ConnectionDialog}. Companion to
 * {@link SelectConnectionToolCallback}, which is reserved for the "pick an existing connection" intent — this one is
 * reserved for the "create a new connection" intent.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class CreateConnectionToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CreateConnectionToolCallback.class);

    private static final String DESCRIPTION = """
        Request the user to create a connection for a specific component — for example Slack,
        Gmail, HubSpot, or any other integration. Call this when the user says "connect Slack",
        "add my Gmail account", "hook up HubSpot", etc. The client renders a "Connect
        <ComponentLabel>" button inline in the chat; clicking it opens the ConnectionDialog
        prefilled with the component. Do NOT try to create connections via any other tool —
        the UI handles credentials, OAuth, and persistence.""";

    private static final String INPUT_SCHEMA =
        """
            {
                "type": "object",
                "properties": {
                    "componentName": {
                        "type": "string",
                        "description": "Lowercase slug of the component to connect — e.g. \\"slack\\", \\"gmail\\", \\"hubspot\\""
                    },
                    "suggestedName": {
                        "type": "string",
                        "description": "Optional suggested display name for the new connection"
                    }
                },
                "required": ["componentName"]
            }""";

    private final ComponentDefinitionService componentDefinitionService;
    private final JsonMapper jsonMapper;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public CreateConnectionToolCallback(
        ComponentDefinitionService componentDefinitionService, JsonMapper jsonMapper) {

        this.componentDefinitionService = componentDefinitionService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("createConnection")
            .description(DESCRIPTION)
            .inputSchema(INPUT_SCHEMA)
            .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        try {
            CreateConnectionInput input = jsonMapper.readValue(toolInput, CreateConnectionInput.class);

            String componentName = input.componentName();

            if (componentName == null || componentName.isBlank()) {
                return toolError("componentName is required and must not be blank");
            }

            String componentLabel = resolveComponentLabel(componentName);

            return jsonMapper.writeValueAsString(
                new CreateConnectionOutput("create-connection", componentName, componentLabel,
                    input.suggestedName()));
        } catch (JacksonException exception) {
            // Malformed tool input is a recoverable LLM error — return a typed tool error so the agent can retry.
            // Log at WARN so a sudden spike (bad system prompt, schema drift) is visible in production logs without
            // requiring user reports. Sanitize the input excerpt to keep newlines / control chars out of the log.
            log.warn(
                "createConnection rejected malformed tool input: {} — first 200 chars of input: {}",
                exception.getMessage(),
                LogSanitizer.sanitizeForLog(
                    toolInput == null ? "<null>" : toolInput.substring(0, Math.min(toolInput.length(), 200))));

            return toolError("Invalid tool input: " + exception.getMessage());
        } catch (RuntimeException exception) {
            // Outer guard mirrors every other subagent callback in this directory. A future serializer NPE,
            // ComponentDefinitionService DataAccessException, or other runtime failure would otherwise propagate
            // raw and abort the entire agent run.
            return ToolErrors.runtimeFailure(
                jsonMapper, CreateConnectionToolCallback.class, "createConnection", exception);
        }
    }

    private String resolveComponentLabel(String componentName) {
        try {
            Optional<ComponentDefinition> componentDefinition =
                componentDefinitionService.fetchComponentDefinition(componentName, null);

            return componentDefinition
                .map(ComponentDefinition::getTitle)
                .filter(title -> title != null && !title.isBlank())
                .orElse(componentName);
        } catch (RuntimeException exception) {
            // Catch RuntimeException (not Exception) — a downstream NPE/DB error must not break the
            // create-connection flow (the user just gets the lowercase slug as the button label). Narrowing from
            // Exception means a future signature change that introduces a checked exception breaks the build
            // instead of being silently swallowed. We MUST still log so a real outage isn't masked behind
            // silent fallback labels.
            log.warn(
                "Failed to resolve display label for component '{}'; falling back to the slug. Reason: {}",
                LogSanitizer.sanitizeForLog(componentName), exception.getMessage(), exception);

            return componentName;
        }
    }

    private String toolError(String message) {
        return ToolErrors.toolError(jsonMapper, message);
    }

    public record CreateConnectionInput(String componentName, @Nullable String suggestedName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateConnectionOutput(String kind, String componentName, String componentLabel,
        @Nullable String suggestedName) {
    }
}
