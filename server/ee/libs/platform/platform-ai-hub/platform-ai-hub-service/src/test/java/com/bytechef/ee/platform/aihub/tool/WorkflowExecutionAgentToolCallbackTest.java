/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.ToolSpec;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class WorkflowExecutionAgentToolCallbackTest {

    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    void testCallReturnsResultWhenSubagentSucceeds() {
        String synthesised = "The run failed because the HTTP task got a 404.";

        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec responseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        stubToolsLambda(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(synthesised);

        WorkflowExecutionAgentToolCallback callback = new WorkflowExecutionAgentToolCallback(chatClient);

        String result = callback.call("{\"request\":\"why did the last run fail?\"}");

        assertThat(result).isEqualTo(synthesised);
    }

    @Test
    void testCallReturnsErrorWhenRequestIsBlank() {
        WorkflowExecutionAgentToolCallback callback = new WorkflowExecutionAgentToolCallback(mock(ChatClient.class));

        String result = callback.call("{\"request\":\"   \"}");

        assertThat(result).contains("error");
        assertThat(result).containsIgnoringCase("request is required");
    }

    @Test
    void testCallReturnsErrorOnInvalidJson() {
        WorkflowExecutionAgentToolCallback callback = new WorkflowExecutionAgentToolCallback(mock(ChatClient.class));

        String result = callback.call("not-json");

        assertThat(result).contains("error");
        assertThat(result).containsIgnoringCase("invalid tool input");
    }

    @Test
    void testCallReturnsToolErrorWhenSubagentReturnsNull() throws Exception {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec responseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        stubToolsLambda(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(null);

        WorkflowExecutionAgentToolCallback callback = new WorkflowExecutionAgentToolCallback(chatClient);

        String result = callback.call("{\"request\":\"any\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.has("error")).isTrue();
        assertThat(node.get("error")
            .asText()).containsIgnoringCase("returned null");
    }

    @Test
    void testCallReturnsToolErrorWhenSubagentThrows() throws Exception {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec responseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        stubToolsLambda(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenThrow(new RuntimeException("execution facade unavailable"));

        WorkflowExecutionAgentToolCallback callback = new WorkflowExecutionAgentToolCallback(chatClient);

        String result = callback.call("{\"request\":\"any\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.get("error")
            .asText())
                .as("payload must surface tool name")
                .contains("workflow_execution_agent failed")
                .as("payload must NOT leak the exception getMessage()")
                .doesNotContain("execution facade unavailable");
    }

    @Test
    void testCallForwardsParentToolContextToSubagent() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec responseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        ToolSpec toolSpec = stubToolsLambda(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("ok");

        AiHubToolInvocationContext invocationContext =
            new AiHubToolInvocationContext(11L, 42L, (short) 0, "diagnose a run", 1L);

        Map<String, Object> parentContextMap = invocationContext.toToolContext();

        ToolContext parentToolContext = new ToolContext(parentContextMap);

        WorkflowExecutionAgentToolCallback callback = new WorkflowExecutionAgentToolCallback(chatClient);

        callback.call("{\"request\":\"any\"}", parentToolContext);

        verify(toolSpec).context(parentContextMap);
    }

    @Test
    void testCallForwardsEmptyMapWhenParentToolContextIsNull() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec responseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        ToolSpec toolSpec = stubToolsLambda(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("ok");

        WorkflowExecutionAgentToolCallback callback = new WorkflowExecutionAgentToolCallback(chatClient);

        callback.call("{\"request\":\"any\"}", null);

        verify(toolSpec).context(Map.of());
    }

    @Test
    void testToolDefinitionExposesWorkflowExecutionAgentNameAndRequestSchema() {
        WorkflowExecutionAgentToolCallback callback = new WorkflowExecutionAgentToolCallback(mock(ChatClient.class));

        assertThat(callback.getToolDefinition()
            .name()).isEqualTo("workflow_execution_agent");
        assertThat(callback.getToolDefinition()
            .inputSchema()).contains("\"request\"");
    }

    private static Stream<Arguments> upstreamFailures() {
        return Stream.of(
            Arguments.of(WebClientResponseException.create(400, "Bad Request", null, null, null)),
            Arguments.of(WebClientResponseException.create(503, "Service Unavailable", null, null, null)),
            Arguments.of(new RuntimeException(new IOException("connection reset"))),
            Arguments.of(new RuntimeException(new TimeoutException("upstream timeout"))),
            Arguments.of(new NullPointerException("malformed response")));
    }

    @ParameterizedTest
    @MethodSource("upstreamFailures")
    void testCallSurfacesAllRuntimeExceptionTypesAsToolError(RuntimeException upstreamException) throws Exception {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec responseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        stubToolsLambda(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenThrow(upstreamException);

        WorkflowExecutionAgentToolCallback callback = new WorkflowExecutionAgentToolCallback(chatClient);

        String result = callback.call("{\"request\":\"any\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.has("error")).isTrue();
        assertThat(node.get("error")
            .asText()).contains("workflow_execution_agent failed");
    }

    @SuppressWarnings("unchecked")
    private static ToolSpec stubToolsLambda(ChatClientRequestSpec requestSpec) {
        ToolSpec toolSpec = mock(ToolSpec.class);

        when(toolSpec.context(anyMap())).thenReturn(toolSpec);
        when(requestSpec.tools(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ToolSpec> consumer = invocation.getArgument(0);

            consumer.accept(toolSpec);

            return requestSpec;
        });

        return toolSpec;
    }
}
