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

package com.bytechef.component.ai.agent.guardrails.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/**
 * Pins how Spring AI resolves two advisors that share an {@code Ordered} value, because the guardrail layers depend on
 * it: {@code CheckForViolationsAdvisor} (per-node, CE) and the workspace {@code AiGuardrailsAdvisor} (EE) both declare
 * {@code HIGHEST_PRECEDENCE}, and which one wraps the other decides whether a node-level output check sees tokens or
 * the caller's restored values.
 *
 * <p>
 * {@code DefaultChatClient#buildAdvisorChain} hands every advisor to {@code DefaultAroundAdvisorChain.Builder#pushAll},
 * which {@code Deque#push}es each one -- an {@code addFirst} -- and then runs a stable {@code OrderComparator} sort
 * over the result. Reversed and then stably sorted, a tie resolves to the advisor registered LAST. Registration order
 * is therefore a tie-breaker, but the inverse of the one a reader assumes.
 * </p>
 *
 * @author Ivica Cardic
 */
class SpringAiTiedAdvisorOrderTest {

    @Test
    void testOnAnOrderTieTheLaterRegisteredAdvisorRunsFirst() {
        List<String> trace = new ArrayList<>();

        ChatClient.create(stubChatModel())
            .prompt("hello")
            .advisors(new TracingAdvisor("registered-first", Ordered.HIGHEST_PRECEDENCE, trace))
            .advisors(new TracingAdvisor("registered-second", Ordered.HIGHEST_PRECEDENCE, trace))
            .call()
            .content();

        assertThat(trace)
            .as("request direction runs outermost first; the later registration is outermost on a tie")
            .containsExactly("registered-second", "registered-first");
    }

    @Test
    void testADistinctOrderValueWinsRegardlessOfRegistration() {
        // Negative control: the order value decides whenever it can. Only a genuine tie falls through to the
        // registration-derived tie-break the test above pins.
        List<String> trace = new ArrayList<>();

        ChatClient.create(stubChatModel())
            .prompt("hello")
            .advisors(new TracingAdvisor("registered-first-lower-precedence", Ordered.HIGHEST_PRECEDENCE + 1, trace))
            .advisors(new TracingAdvisor("registered-second-highest", Ordered.HIGHEST_PRECEDENCE, trace))
            .call()
            .content();

        assertThat(trace).containsExactly("registered-second-highest", "registered-first-lower-precedence");

        trace.clear();

        ChatClient.create(stubChatModel())
            .prompt("hello")
            .advisors(new TracingAdvisor("registered-first-highest", Ordered.HIGHEST_PRECEDENCE, trace))
            .advisors(new TracingAdvisor("registered-second-lower-precedence", Ordered.HIGHEST_PRECEDENCE + 1, trace))
            .call()
            .content();

        assertThat(trace).containsExactly("registered-first-highest", "registered-second-lower-precedence");
    }

    private static ChatModel stubChatModel() {
        return new ChatModel() {

            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
        };
    }

    private record TracingAdvisor(String name, int order, List<String> trace) implements CallAdvisor {

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            trace.add(name);

            return chain.nextCall(request);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
