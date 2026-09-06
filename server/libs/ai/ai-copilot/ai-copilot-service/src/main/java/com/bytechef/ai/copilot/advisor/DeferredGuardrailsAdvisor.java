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

package com.bytechef.ai.copilot.advisor;

import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.commons.util.NumberUtils;
import com.bytechef.platform.ai.guardrails.AiGuardrailsAdvisorProvider;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

/**
 * Occupies a fixed position in a Copilot agent's advisor chain and resolves the real guardrails advisor on every call
 * rather than once when the chain is built.
 *
 * <p>
 * Every {@code *SpringAIAgent} bean in {@code ai-copilot-service} is a singleton whose advisor list is fixed at
 * construction, so {@link CopilotGuardrailsAdvisorFactory#guardrailsAdvisors()} ran exactly once per bean, at Spring
 * context refresh. {@code AiGuardrailsAdvisorProvider#getAdvisorForWorkspace} returns empty when
 * {@code AiGuardrails#isActive} says every guardrail category is disabled for the resolved workspace - and that is a
 * runtime setting, editable from the guardrails settings UI, not a boot-time property. The resulting staleness was
 * asymmetric in the wrong direction:
 * </p>
 * <ul>
 * <li><b>Enabled after boot</b> - nothing happened. No advisor was attached at construction, so Copilot stayed
 * completely unguarded for the life of the JVM while the settings UI reported guardrails as on.</li>
 * <li><b>Disabled after boot</b> - mostly self-corrected, because the attached {@code AiGuardrailsAdvisor} re-reads the
 * workspace policy on each call and finds every category off.</li>
 * </ul>
 *
 * <p>
 * Resolving per call fixes the dangerous direction and costs one {@code ObjectProvider#getIfAvailable} plus one policy
 * lookup per request - the same lookup the resolved advisor performs anyway. When nothing resolves, this advisor passes
 * straight through to the rest of the chain, so a CE build (no {@code AiGuardrailsAdvisorProvider} bean) and a
 * workspace with guardrails off behave exactly as they did before.
 * </p>
 *
 * <p>
 * {@link #getOrder()} returns {@link Advisor#HIGHEST_PRECEDENCE}, matching what {@code AiGuardrailsAdvisor} itself
 * returns, so substituting this advisor for it leaves chain ordering unchanged.
 * </p>
 *
 * @author Ivica Cardic
 */
final class DeferredGuardrailsAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String NAME = "deferredGuardrails";

    private final ObjectProvider<AiGuardrailsAdvisorProvider> aiGuardrailsAdvisorProviderProvider;
    private final String surface;

    DeferredGuardrailsAdvisor(
        ObjectProvider<AiGuardrailsAdvisorProvider> aiGuardrailsAdvisorProviderProvider, String surface) {

        this.aiGuardrailsAdvisorProviderProvider = aiGuardrailsAdvisorProviderProvider;
        this.surface = surface;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        Advisor advisor = resolveAdvisor(chatClientRequest);

        if (advisor instanceof CallAdvisor callAdvisor) {
            return callAdvisor.adviseCall(chatClientRequest, callAdvisorChain);
        }

        return callAdvisorChain.nextCall(chatClientRequest);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
        ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {

        Advisor advisor = resolveAdvisor(chatClientRequest);

        if (advisor instanceof StreamAdvisor streamAdvisor) {
            return streamAdvisor.adviseStream(chatClientRequest, streamAdvisorChain);
        }

        return streamAdvisorChain.nextStream(chatClientRequest);
    }

    private @Nullable Advisor resolveAdvisor(ChatClientRequest chatClientRequest) {
        AiGuardrailsAdvisorProvider aiGuardrailsAdvisorProvider = aiGuardrailsAdvisorProviderProvider.getIfAvailable();

        if (aiGuardrailsAdvisorProvider == null) {
            return null;
        }

        return aiGuardrailsAdvisorProvider.getAdvisorForWorkspace(workspaceId(chatClientRequest), surface)
            .orElse(null);
    }

    /**
     * Reads the session's server-verified workspace off the prompt's tool context -- the channel
     * {@code AgentToolInvocationContext} already travels on, and the one {@code AiGuardrailsAdvisor} reads for its
     * {@code PiiTokenSession}. Returns {@code null} when the prompt carries none, which resolves the tenant-default
     * row: not every Copilot surface carries a workspace, and failing the call would be a worse outcome than applying
     * the tenant default on a feature most tenants have switched off.
     */
    private static @Nullable Long workspaceId(ChatClientRequest chatClientRequest) {
        Prompt prompt = chatClientRequest.prompt();

        if (!(prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions)) {
            return null;
        }

        Map<String, Object> toolContext = toolCallingChatOptions.getToolContext();

        if (CollectionUtils.isEmpty(toolContext)) {
            return null;
        }

        return NumberUtils.asLong(toolContext.get(AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY));
    }
}
