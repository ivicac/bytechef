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

package com.bytechef.component.ai.agent.tool;

import com.bytechef.platform.ai.sensitivedata.SensitiveDataMetrics;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenBoundaryToolCallingManager;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Supplies the {@link ToolCallingManager} an agent run should use.
 *
 * <p>
 * Spring AI's tool call limits ({@code ToolCallLimits}, 2.0.1+) are fixed when the manager is built and are not
 * expressible per request - {@code ToolCallingChatOptions} carries no limit, and the only public configuration surface
 * besides the builder is the application-wide {@code spring.ai.tools.limits.*} properties. A per-agent cap therefore
 * needs its own manager, which is why the application's collaborators are collected here rather than left implicit:
 * building one from {@code DefaultToolCallingManager.builder()} defaults would quietly substitute a different callback
 * resolver and, more damagingly, a different {@link ToolExecutionExceptionProcessor}, changing whether a failing tool
 * is reported back to the model or thrown.
 *
 * <p>
 * Existing as a bean keeps this out of {@code AiAgentComponentHandler}'s constructor, which already telescopes through
 * seven parameters.
 *
 * @author Ivica Cardic
 */
@Component
public final class AgentToolCallingManagers {

    private final ToolCallingManager defaultToolCallingManager;
    private final @Nullable ToolCallbackResolver toolCallbackResolver;
    private final @Nullable ToolExecutionExceptionProcessor toolExecutionExceptionProcessor;
    private final ObservationRegistry observationRegistry;
    private final SensitiveDataRedactor sensitiveDataRedactor;

    /**
     * The application's manager alone, with no ability to build a limited variant of it — a configured
     * {@code maxToolCalls} is then honoured as "no cap" rather than enforced. For callers that assemble an agent
     * outside a Spring context. There is no {@link SensitiveDataRedactor} bean to fall back on here either, so this
     * constructor builds a detector-less one — {@link #getToolCallingManager} still restores PII tokens in tool-call
     * arguments (that only needs the {@code PiiTokenSession} on the prompt's tool context, not a detector), but tool
     * results pass through without tokenization/redaction, same as any other zero-detector deployment.
     */
    public AgentToolCallingManagers(ToolCallingManager defaultToolCallingManager) {
        this.defaultToolCallingManager = defaultToolCallingManager;
        this.toolCallbackResolver = null;
        this.toolExecutionExceptionProcessor = null;
        this.observationRegistry = ObservationRegistry.NOOP;
        this.sensitiveDataRedactor = new SensitiveDataRedactor(List.of());
    }

    /**
     * Load-bearing {@code @Autowired}, not decoration. Spring uses a sole constructor implicitly, but this class has
     * two, so without the annotation it falls back to a no-arg constructor that does not exist and the whole context
     * fails with "No default constructor found" -- a message that points at the constructor this class lacks rather
     * than at the one too many it has.
     *
     * <p>
     * Deliberately takes no {@code ObjectProvider<SensitiveDataMetrics>}: this class is a singleton bean shared by
     * every AI Agent run in the JVM, while the metrics instance the tool boundary needs to record through is per-call
     * and {@code surface}-tagged for the caller ({@code "ai_agent"}). The only production {@link SensitiveDataMetrics}
     * implementation, EE's {@code AiGuardrailMetrics}, is a constructor argument (`surface`) away from being usable
     * here even if it were injectable -- there is no single instance correct for more than one call.
     * {@link #getToolCallingManager} therefore takes the metrics as a per-call parameter instead; see that method's
     * javadoc.
     * </p>
     */
    @Autowired
    public AgentToolCallingManagers(
        ToolCallingManager defaultToolCallingManager, ObjectProvider<ToolCallbackResolver> toolCallbackResolverProvider,
        ObjectProvider<ToolExecutionExceptionProcessor> toolExecutionExceptionProcessorProvider,
        ObjectProvider<ObservationRegistry> observationRegistryProvider,
        ObjectProvider<SensitiveDataRedactor> sensitiveDataRedactorProvider) {

        this.defaultToolCallingManager = defaultToolCallingManager;
        this.toolCallbackResolver = toolCallbackResolverProvider.getIfAvailable();
        this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessorProvider.getIfAvailable();
        this.observationRegistry = observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
        this.sensitiveDataRedactor =
            sensitiveDataRedactorProvider.getIfAvailable(() -> new SensitiveDataRedactor(List.of()));
    }

    /**
     * The application's own manager when {@code maxToolCalls} is unset, otherwise a copy of it carrying that total
     * limit. The per-tool cap is lifted on the copy ({@code DEFAULT_MAX_CALLS_PER_TOOL} is 40) so that the one number
     * the agent configures is the only one that can stop it - a total of 100 that silently became 40 for the tool the
     * agent actually leans on would be indistinguishable from the agent giving up early.
     *
     * <p>
     * Falls back to the application's manager, uncapped, if either collaborator is missing rather than rebuilding from
     * defaults; an unenforced limit is a smaller surprise than tool errors changing shape.
     *
     * <p>
     * Either way, the manager returned is wrapped with {@link PiiTokenBoundaryToolCallingManager} before it reaches the
     * caller -- every AI Agent action (including realtime, whose WebSocket layer is transport around this same
     * streaming call) goes through this one factory, so wrapping here is the single point that covers all three.
     * </p>
     *
     * @param maxToolCalls the per-agent total tool-call cap, or {@code null} for none
     * @param metrics      the {@code surface}-tagged {@link SensitiveDataMetrics} instance the wrapped manager's own
     *                     {@code tool_args_restored}/{@code token_unresolved}/{@code tool_result_tokenized} events
     *                     should be recorded through, or {@code null} to record nothing. Passed in per call rather than
     *                     resolved from an injected {@code ObjectProvider<SensitiveDataMetrics>} -- this class is a
     *                     singleton bean, but the correct metrics instance is per-call and {@code surface}-tagged for
     *                     the caller (see {@code AiGuardrailsAdvisorProvider#getMetrics}, which callers such as
     *                     {@code AbstractAiAgentChatAction} resolve alongside their guardrails advisor and pass in
     *                     here).
     */
    public ToolCallingManager getToolCallingManager(
        @Nullable Integer maxToolCalls, @Nullable SensitiveDataMetrics metrics) {

        ToolCallingManager toolCallingManager;

        if (maxToolCalls == null || toolCallbackResolver == null || toolExecutionExceptionProcessor == null) {
            toolCallingManager = defaultToolCallingManager;
        } else {
            toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .toolCallbackResolver(toolCallbackResolver)
                .toolExecutionExceptionProcessor(toolExecutionExceptionProcessor)
                .unlimitedCallsPerTool()
                .maxTotalToolCalls(maxToolCalls)
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build();
        }

        return PiiTokenBoundaryToolCallingManager.wrap(toolCallingManager, sensitiveDataRedactor, () -> metrics);
    }
}
