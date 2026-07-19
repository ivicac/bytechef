/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.ai.a2a;

import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.Part;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Handles A2A (Agent2Agent) JSON-RPC requests for a single exposed agent, bridging them to an {@link A2AAgentExecutor}.
 * The addressed agent is identified by the endpoint (URL path), not by the message body, so the caller supplies the
 * {@code agentId}.
 *
 * <p>
 * Only the non-streaming {@code message/send} method is handled: the inbound message's text parts are concatenated, the
 * agent is executed, and the response is returned as a completed (or failed) A2A {@link Task}. Streaming
 * ({@code message/stream}) and task lifecycle queries ({@code tasks/get}) are deliberately out of scope for this slice
 * and surface as method-not-found.
 * </p>
 *
 * @author Ivica Cardic
 */
public class A2AProtocolHandler {

    public static final String METHOD_SEND_MESSAGE = "message/send";

    private final A2AAgentExecutor agentExecutor;

    public A2AProtocolHandler(A2AAgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }

    /**
     * Dispatches a parsed JSON-RPC request for the addressed agent and returns the JSON-RPC response.
     *
     * @param agentId   the addressed agent (derived from the request path)
     * @param requestId the JSON-RPC request id to echo back
     * @param method    the JSON-RPC method
     * @param params    the parsed {@code message/send} params, or {@code null} for other methods
     * @return a {@link SendMessageResponse} on success, or a {@link JSONRPCErrorResponse} on any protocol error
     */
    public JSONRPCResponse<?> handle(
        String agentId, @Nullable Object requestId, String method, @Nullable MessageSendParams params) {

        if (!METHOD_SEND_MESSAGE.equals(method)) {
            return new JSONRPCErrorResponse(requestId, new MethodNotFoundError());
        }

        if (params == null || params.message() == null) {
            return new JSONRPCErrorResponse(requestId, new InvalidParamsError("A message is required"));
        }

        Message inboundMessage = params.message();
        String text = extractText(inboundMessage);

        if (text.isBlank()) {
            return new JSONRPCErrorResponse(requestId, new InvalidParamsError("The message has no text content"));
        }

        String contextId = inboundMessage.getContextId();

        A2AAgentRequest agentRequest = new A2AAgentRequest(
            agentId, text, contextId, inboundMessage.getMessageId());

        A2AAgentResult agentResult;

        try {
            agentResult = agentExecutor.execute(agentRequest);
        } catch (Exception exception) {
            agentResult = A2AAgentResult.ofError(exception.getMessage());
        }

        Task task = toTask(contextId, agentResult);

        return new SendMessageResponse(requestId, task);
    }

    /**
     * Concatenates the text of every {@link TextPart} in the message, ignoring non-text parts.
     */
    static String extractText(Message message) {
        StringBuilder textBuilder = new StringBuilder();

        List<Part<?>> parts = message.getParts();

        if (parts != null) {
            for (Part<?> part : parts) {
                if (part instanceof TextPart textPart) {
                    textBuilder.append(textPart.getText());
                }
            }
        }

        return textBuilder.toString();
    }

    private Task toTask(@Nullable String contextId, A2AAgentResult agentResult) {
        String taskId = UUID.randomUUID()
            .toString();
        String effectiveContextId = contextId != null ? contextId : UUID.randomUUID()
            .toString();

        TaskState taskState = agentResult.success() ? TaskState.COMPLETED : TaskState.FAILED;
        String responseText = agentResult.success()
            ? agentResult.text()
            : "Error: " + agentResult.errorMessage();

        Message agentMessage = new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(new TextPart(responseText))
            .messageId(UUID.randomUUID()
                .toString())
            .contextId(effectiveContextId)
            .taskId(taskId)
            .build();

        TaskStatus taskStatus = new TaskStatus(taskState, agentMessage, null);

        return new Task(taskId, effectiveContextId, taskStatus, List.of(), List.of(), null);
    }
}
