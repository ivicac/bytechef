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

import static org.assertj.core.api.Assertions.assertThat;

import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class A2AProtocolHandlerTest {

    @Test
    void testSendMessageExecutesAgentAndReturnsCompletedTask() {
        AtomicReference<A2AAgentRequest> capturedRequest = new AtomicReference<>();

        A2AProtocolHandler handler = new A2AProtocolHandler(request -> {
            capturedRequest.set(request);

            return A2AAgentResult.ofText("42");
        });

        MessageSendParams params = new MessageSendParams(userMessage("What is 6 times 7?"), null, null);

        JSONRPCResponse<?> response = handler.handle(
            "agent-1", "req-1", A2AProtocolHandler.METHOD_SEND_MESSAGE, params);

        assertThat(response).isInstanceOf(SendMessageResponse.class);
        assertThat(capturedRequest.get()
            .agentId()).isEqualTo("agent-1");
        assertThat(capturedRequest.get()
            .text()).isEqualTo("What is 6 times 7?");

        Task task = (Task) ((SendMessageResponse) response).getResult();

        assertThat(task.getStatus()
            .state()).isEqualTo(TaskState.COMPLETED);
        assertThat(A2AProtocolHandler.extractText(task.getStatus()
            .message())).isEqualTo("42");
    }

    @Test
    void testFailedAgentResultProducesFailedTask() {
        A2AProtocolHandler handler = new A2AProtocolHandler(request -> A2AAgentResult.ofError("boom"));

        MessageSendParams params = new MessageSendParams(userMessage("hi"), null, null);

        JSONRPCResponse<?> response = handler.handle(
            "agent-1", "req-2", A2AProtocolHandler.METHOD_SEND_MESSAGE, params);

        Task task = (Task) ((SendMessageResponse) response).getResult();

        assertThat(task.getStatus()
            .state()).isEqualTo(TaskState.FAILED);
        assertThat(A2AProtocolHandler.extractText(task.getStatus()
            .message())).contains("boom");
    }

    @Test
    void testAgentExceptionIsSurfacedAsFailedTask() {
        A2AProtocolHandler handler = new A2AProtocolHandler(request -> {
            throw new IllegalStateException("kaboom");
        });

        MessageSendParams params = new MessageSendParams(userMessage("hi"), null, null);

        JSONRPCResponse<?> response = handler.handle(
            "agent-1", "req-3", A2AProtocolHandler.METHOD_SEND_MESSAGE, params);

        Task task = (Task) ((SendMessageResponse) response).getResult();

        assertThat(task.getStatus()
            .state()).isEqualTo(TaskState.FAILED);
    }

    @Test
    void testUnknownMethodReturnsMethodNotFound() {
        A2AProtocolHandler handler = new A2AProtocolHandler(request -> A2AAgentResult.ofText("x"));

        JSONRPCResponse<?> response = handler.handle("agent-1", "req-4", "tasks/get", null);

        assertThat(response).isInstanceOf(JSONRPCErrorResponse.class);
        assertThat(((JSONRPCErrorResponse) response).getError()
            .getCode()).isEqualTo(-32601);
    }

    @Test
    void testMissingMessageReturnsInvalidParams() {
        A2AProtocolHandler handler = new A2AProtocolHandler(request -> A2AAgentResult.ofText("x"));

        JSONRPCResponse<?> response = handler.handle(
            "agent-1", "req-5", A2AProtocolHandler.METHOD_SEND_MESSAGE, null);

        assertThat(response).isInstanceOf(JSONRPCErrorResponse.class);
        assertThat(((JSONRPCErrorResponse) response).getError()
            .getCode()).isEqualTo(-32602);
    }

    private static Message userMessage(String text) {
        return new Message.Builder()
            .role(Message.Role.USER)
            .parts(new TextPart(text))
            .messageId("m-1")
            .build();
    }
}
