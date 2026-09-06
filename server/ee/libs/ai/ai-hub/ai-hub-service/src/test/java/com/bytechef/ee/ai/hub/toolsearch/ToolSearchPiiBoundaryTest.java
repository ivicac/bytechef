/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSessionToolContext;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;

/**
 * Pins the behaviour spec decision D2 was made to obtain: a tool the model resolves DYNAMICALLY, at request time, still
 * gets its arguments restored at the PII boundary.
 *
 * <p>
 * It does not, and cannot, pin D2 itself -- the choice to decorate the {@link ToolCallingManager} rather than each
 * {@link ToolCallback}. D2 is a claim about which wrapping hooks exist in the AI Hub's wiring, and no execution test
 * can demonstrate the absence of a hook: this test shows the chosen design works, not that the rejected one would have
 * failed. Read the paragraph below as the argument for D2 and this test as its consequence, not its proof. (An earlier
 * version of this javadoc, and of {@code .agents/ai-guardrails.md}, claimed the stronger thing.)
 * </p>
 *
 * <p>
 * The argument: the AI Hub's tool-search catalog callbacks are never wrapped -- or even known -- at the point
 * {@code AiHubConfiguration} assembles the agent's own static tool list. A per-callback decorator (as
 * {@code ApprovalGateToolCallback}, {@code RehydrateContextToolCallback}, {@code MeteredToolCallback} and
 * {@code ProgressReportingToolCallback} all are), applied where tools are registered, would never see one of these:
 * {@code AiHubClusterElementToolCallbacks} builds them lazily in a supplier feeding
 * {@link ToolSearchAdvisorConfiguration#buildToolCallingManager}'s resolver map, and the base
 * {@code ToolSearchToolCallingAdvisor}'s {@code prepareIteration} only splices a matching callback onto the CURRENT
 * request's {@code ToolCallingChatOptions.toolCallbacks()} once the model has searched for and named it -- at request
 * time, per iteration, never at agent-construction time.
 * </p>
 *
 * <p>
 * This test reproduces exactly that: the callback is added to the {@link Prompt}'s options the same way
 * {@code prepareIteration} would (never passed to any construction-time wrapping step), and is ALSO the sole entry in
 * the resolver map {@link ToolSearchAdvisorConfiguration#buildToolCallingManager} builds a
 * {@code MapToolCallbackResolver} from, matching production's actual wiring. (Spring AI 2.0.1's
 * {@code DefaultToolCallingManager} only consults that resolver when built with {@code resolutionFallbackEnabled(true)}
 * -- unset here, as in production -- so the options-list placement, not the resolver, is what makes the call resolvable
 * in this test, exactly as it is in the real advisor loop.)
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ToolSearchPiiBoundaryTest {

    @Test
    void testADynamicallyResolvedToolStillGetsItsArgumentsRestored() {
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        // Never passed to any construction-time wrapping step -- only known to the manager built below.
        RecordingToolCallback dynamic = new RecordingToolCallback("sendEmail");
        ToolCallingManager manager = toolSearchManagerResolving(dynamic);

        manager.executeToolCalls(
            promptWithSession(session, dynamic), chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

        assertThat(dynamic.lastInput()).isEqualTo("{\"to\":\"bob@acme.io\"}");
    }

    @Test
    void testWithNoSessionADynamicallyResolvedToolSeesArgumentsUnchanged() {
        RecordingToolCallback dynamic = new RecordingToolCallback("sendEmail");
        ToolCallingManager manager = toolSearchManagerResolving(dynamic);

        manager.executeToolCalls(
            promptWithoutSession(dynamic), chatResponseWithToolCall("sendEmail", "{\"to\":\"x\"}"));

        assertThat(dynamic.lastInput()).isEqualTo("{\"to\":\"x\"}");
    }

    /**
     * Builds the exact manager shape {@link ToolSearchAdvisorConfiguration#buildToolCallingManager} composes for both
     * AI Hub modes -- {@code MapToolCallbackResolver} inside {@code DefaultToolCallingManager} inside
     * {@code UnknownToolRecoveringToolCallingManager} inside {@code LazyToolCallingManager} inside the PII boundary --
     * with the resolver map holding {@code dynamic}, matching how production seeds it from the catalog supplier.
     */
    private static ToolCallingManager toolSearchManagerResolving(ToolCallback dynamic) {
        return ToolSearchAdvisorConfiguration.buildToolCallingManager(
            ObservationRegistry.NOOP, () -> Map.of(dynamic.getToolDefinition()
                .name(), dynamic),
            new DefaultToolExecutionExceptionProcessor(false), new SensitiveDataRedactor(List.of()), null);
    }

    private static Prompt promptWithSession(PiiTokenSession session, ToolCallback dynamicallyDiscovered) {
        return new Prompt(
            List.of(),
            ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(dynamicallyDiscovered))
                .toolContext(PiiTokenSessionToolContext.into(Map.of(), session))
                .build());
    }

    private static Prompt promptWithoutSession(ToolCallback dynamicallyDiscovered) {
        return new Prompt(
            List.of(),
            ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(dynamicallyDiscovered))
                .build());
    }

    private static ChatResponse chatResponseWithToolCall(String toolName, String arguments) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", toolName, arguments);
        AssistantMessage assistantMessage = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(toolCall))
            .build();

        return new ChatResponse(List.of(new Generation(assistantMessage)));
    }

    private static final class RecordingToolCallback implements ToolCallback {

        private final String name;

        private @Nullable String lastInput;

        private RecordingToolCallback(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name(name)
                .description("recording tool")
                .inputSchema("{}")
                .build();
        }

        @Override
        public String call(String toolInput) {
            lastInput = toolInput;

            return "ok";
        }

        private String lastInput() {
            String input = lastInput;

            if (input == null) {
                throw new IllegalStateException("call() was never invoked");
            }

            return input;
        }
    }
}
