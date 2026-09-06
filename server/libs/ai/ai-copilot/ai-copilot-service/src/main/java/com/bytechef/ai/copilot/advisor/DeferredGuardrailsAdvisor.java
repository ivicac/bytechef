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

import com.bytechef.platform.ai.guardrails.AiGuardrailsAdvisorProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Occupies a fixed position in a Copilot agent's advisor chain and resolves the real guardrails advisor on every call
 * rather than once when the chain is built.
 *
 * <p>
 * Every {@code *SpringAIAgent} bean in {@code ai-copilot-service} is a singleton whose advisor list is fixed at
 * construction, so {@link CopilotGuardrailsAdvisorFactory#guardrailsAdvisors()} ran exactly once per bean, at Spring
 * context refresh. {@code AiGuardrailsAdvisorProvider#getAdvisor} returns empty when {@code AiGuardrails#isActive} says
 * every guardrail category is disabled for the resolved workspace - and that is a runtime setting, editable from the
 * guardrails settings UI, not a boot-time property. The resulting staleness was asymmetric in the wrong direction:
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
        Advisor advisor = resolveAdvisor();

        if (advisor instanceof CallAdvisor callAdvisor) {
            return callAdvisor.adviseCall(chatClientRequest, callAdvisorChain);
        }

        return callAdvisorChain.nextCall(chatClientRequest);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
        ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {

        Advisor advisor = resolveAdvisor();

        if (advisor instanceof StreamAdvisor streamAdvisor) {
            return streamAdvisor.adviseStream(chatClientRequest, streamAdvisorChain);
        }

        return streamAdvisorChain.nextStream(chatClientRequest);
    }

    private @Nullable Advisor resolveAdvisor() {
        AiGuardrailsAdvisorProvider aiGuardrailsAdvisorProvider = aiGuardrailsAdvisorProviderProvider.getIfAvailable();

        if (aiGuardrailsAdvisorProvider == null) {
            return null;
        }

        return aiGuardrailsAdvisorProvider.getAdvisor(null, null, surface)
            .orElse(null);
    }
}
