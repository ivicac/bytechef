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
import com.bytechef.platform.ai.sensitivedata.SensitiveDataMetrics;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenBoundaryToolCallingManager;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Single seam every {@code *SpringAIAgent.builder()} bean and every AI-Hub-delegation sub-agent
 * {@link org.springframework.ai.chat.client.ChatClient} bean across {@code ai-copilot-service} goes through to attach
 * the guardrails advisor - one bean shared by all nine {@code *Configuration} classes in this module instead of nine
 * copies of the same {@link AiGuardrailsAdvisorProvider} resolution logic. Coverage is enforced by a module-wide source
 * scan (see the guardrails-advisor coverage test in {@code com.bytechef.ai.copilot.config}), not by anything this class
 * itself can check - a caller that never calls {@link #guardrailsAdvisors()} is invisible to it.
 *
 * <p>
 * The {@link DeferredGuardrailsAdvisor} this list carries resolves the session's server-verified workspace - read off
 * the prompt's {@code ToolCallingChatOptions} tool context on every call, the same channel
 * {@code AgentToolInvocationContext} travels on - via {@link AiGuardrailsAdvisorProvider#getAdvisorForWorkspace},
 * falling back to the tenant default ({@code workspaceId = null}) only when the prompt carries none. The tool-boundary
 * metrics supplier below is the one remaining caller still pinned to the tenant default; see {@link #getMetrics()} for
 * why.
 * </p>
 *
 * <p>
 * <b>Tool boundary.</b> Since every caller of {@link #guardrailsAdvisors()} also relies on it for the tool-calling
 * advisor -- neither shape (a plain {@code ChatClient.Builder} nor the vendored {@code SpringAIAgent.Builder}) exposes
 * a way to set a {@link ToolCallingManager} directly in Spring AI 2.0.1, and {@code DefaultChatClient} auto-registers a
 * plain, unwrapped one on any request whose advisor chain carries no {@code ToolAdvisor} -- the returned list always
 * carries one more advisor: a {@link ToolCallingAdvisor} built with a {@link ToolCallingManager} wrapped by
 * {@link PiiTokenBoundaryToolCallingManager}, mirroring {@code AgentToolCallingManagers} (the canvas AI Agent
 * component) and {@code ToolSearchAdvisorConfiguration} (AI Hub). Registering it here -- rather than at each of the 47
 * call sites -- means every {@code ChatClient}/{@code SpringAIAgent} built through this factory, including the
 * AI-Hub-delegation sub-agent {@code ChatClient} beans that {@code SubAgentGuardrailedChatClient} (ai-hub-service)
 * wraps with more advisors at request time, restores PII tokens in tool-call arguments before a tool runs and tokenizes
 * tool results before they reach the model. Spring AI's {@code DefaultChatClient} rejects an advisor chain carrying
 * more than one {@code ToolAdvisor}, so no caller in this module may register its own.
 * </p>
 *
 * <p>
 * The tool-boundary advisor is attached unconditionally, independent of whether an {@link AiGuardrailsAdvisorProvider}
 * bean is present: {@link PiiTokenBoundaryToolCallingManager} is CE and reads its activation signal (a
 * {@code PiiTokenSession} on the prompt's tool context) per call, not from this factory, and is genuinely inert -- no
 * rebuild, no allocation, delegates straight through -- for every call that carries none, which is every call in a
 * deployment with no guardrails module or with tokenization disabled for the resolved workspace.
 * </p>
 *
 * <p>
 * Guardrails are EE-only. When no {@link AiGuardrailsAdvisorProvider} bean is on the classpath (CE builds, or an EE app
 * that doesn't carry the guardrails module), the {@link DeferredGuardrailsAdvisor} this list carries resolves nothing
 * and passes straight through to the rest of the chain, so this class never requires EE.
 * </p>
 *
 * <p>
 * <b>Nothing here is resolved at construction.</b> Every {@code *SpringAIAgent} bean is a singleton whose advisor list
 * is fixed when the bean is built, so this method runs once per bean at context refresh - while whether guardrails
 * apply is a runtime setting, editable from the guardrails settings UI. Both halves of the returned list therefore
 * defer their resolution to the moment of use: {@link DeferredGuardrailsAdvisor} resolves the guardrails advisor per
 * request (see its javadoc for the asymmetry that made construction-time resolution actively dangerous - enabling
 * guardrails after boot used to do nothing at all), and the tool boundary is wrapped with a metrics <em>supplier</em>
 * rather than a metrics instance, so a workspace that had guardrails off at boot starts recording tool-boundary events
 * as soon as they are switched on instead of staying silent until the next restart.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
public class CopilotGuardrailsAdvisorFactory {

    private static final String GUARDRAILS_SURFACE = "copilot";

    private final ObjectProvider<AiGuardrailsAdvisorProvider> aiGuardrailsAdvisorProviderProvider;
    private final SensitiveDataRedactor sensitiveDataRedactor;

    public CopilotGuardrailsAdvisorFactory(
        ObjectProvider<AiGuardrailsAdvisorProvider> aiGuardrailsAdvisorProviderProvider,
        SensitiveDataRedactor sensitiveDataRedactor) {

        this.aiGuardrailsAdvisorProviderProvider = aiGuardrailsAdvisorProviderProvider;
        this.sensitiveDataRedactor = sensitiveDataRedactor;
    }

    public List<Advisor> guardrailsAdvisors() {
        return List.of(
            new DeferredGuardrailsAdvisor(aiGuardrailsAdvisorProviderProvider, GUARDRAILS_SURFACE),
            toolBoundaryAdvisor());
    }

    /**
     * Builds a guarded {@link ChatClient} over {@code chatModel} for the two EE Copilot generators that call a model
     * once, outside any agent - {@code PropertyCopilotGeneratorImpl} and
     * {@code WorkflowDescriptionCopilotGeneratorImpl} in {@code server/ee/libs/ai/ai-copilot/ai-copilot-service}. Both
     * previously called {@code chatModel.call(new Prompt(...))} directly, which no advisor can intercept:
     * {@code AiGuardrailsAdvisor} is a {@link ChatClient} advisor, so a {@link ChatModel} call reaches the provider
     * without passing through any guardrail by construction, not by oversight. That mattered most for the property
     * generator, whose prompt embeds {@code WorkflowNodeOutputFacade} sample output - real data captured from real test
     * runs against real connections.
     *
     * @param chatModel the model to wrap
     * @return a {@link ChatClient} whose advisor chain carries {@link #guardrailsAdvisors()}
     */
    public ChatClient guardedChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultAdvisors(guardrailsAdvisors())
            .build();
    }

    /**
     * Re-guards an already-built {@link ChatClient} - the shape {@code PropertyCopilotGeneratorImpl} needs for its
     * catalog-override branch, where {@code CatalogChatClientResolver} hands back a finished client rather than a
     * builder. {@code ChatClient.Builder#defaultAdvisors(List)} appends rather than replaces (verified against
     * spring-ai-client-chat 2.0.1: {@code DefaultChatClient.DefaultChatClientRequestSpec#advisors} calls
     * {@code addAll}), so whatever the resolver already attached survives.
     *
     * @param chatClient the client to re-guard
     * @return a {@link ChatClient} whose advisor chain carries {@link #guardrailsAdvisors()} in addition to its own
     */
    public ChatClient guardedChatClient(ChatClient chatClient) {
        return chatClient.mutate()
            .defaultAdvisors(guardrailsAdvisors())
            .build();
    }

    /**
     * Builds the {@link ToolCallingAdvisor} every caller of {@link #guardrailsAdvisors()} attaches so its tool loop
     * runs through {@link PiiTokenBoundaryToolCallingManager} instead of the plain manager
     * {@code DefaultChatClient#autoRegisterToolCallingAdvisor()} would otherwise build unasked. The delegate manager is
     * built the same way that auto-registered default is -- {@code ToolCallingManager.builder().build()} -- so wrapping
     * it here changes nothing about tool resolution for any of the 47 call sites; it only adds the restore/tokenize
     * boundary around it.
     */
    private Advisor toolBoundaryAdvisor() {
        ToolCallingManager toolCallingManager = PiiTokenBoundaryToolCallingManager.wrap(
            ToolCallingManager.builder()
                .build(),
            sensitiveDataRedactor, this::getMetrics);

        return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .build();
    }

    /**
     * Resolves the tool-boundary {@link SensitiveDataMetrics}.
     *
     * <p>
     * This still resolves the tenant default ({@code workspaceId = null}), unlike {@link DeferredGuardrailsAdvisor}.
     * The constraint is not visibility -- {@code PiiTokenBoundaryToolCallingManager#executeToolCalls(Prompt,
     * ChatResponse)} has the prompt and its tool context in scope, and already reads the workspace-resolved advisor's
     * {@code PiiTokenSession} from exactly that map -- it is the {@code Supplier<SensitiveDataMetrics>} shape this
     * factory hands to {@link PiiTokenBoundaryToolCallingManager#wrap}: a zero-argument supplier fixed once here at
     * wrap time, with no per-call channel to pass a workspace through. That shape is shared by two other callers (the
     * canvas AI Agent component and AI Hub's tool search), so widening it belongs to a separate change.
     * </p>
     *
     * <p>
     * Redaction itself is unaffected: whether tool-call arguments are restored and tool results are tokenized/redacted
     * is decided entirely by the {@code PiiTokenSession} and {@code PiiTokenBoundaryPolicy} the workspace-resolved
     * advisor seeds onto the prompt's tool context, both read independently of this metrics supplier. What is lost is
     * observability -- and {@code AiGuardrailMetrics} tags its counters only by {@code event} and {@code surface},
     * carrying no workspace dimension even when one is resolved elsewhere, so this is not a "wrong tag" gap.
     * Concretely: for a workspace that has guardrails on while the tenant default has them off -- the configuration
     * this task exists to support -- {@code getAdvisorForWorkspace} resolves an advisor and redaction runs, while this
     * method's {@code getMetrics(null, null, GUARDRAILS_SURFACE)} call resolves against the (inactive) tenant default
     * and {@code AiGuardrailsAdvisorProviderImpl#buildMetricsIfActive} returns {@code null} for it -- so the tool
     * boundary redacts correctly but records no counters at all for that workspace. Closing this means giving the
     * metrics supplier access to the tool context so it can call
     * {@link AiGuardrailsAdvisorProvider#getMetricsForWorkspace} instead.
     * </p>
     */
    private @Nullable SensitiveDataMetrics getMetrics() {
        AiGuardrailsAdvisorProvider aiGuardrailsAdvisorProvider = aiGuardrailsAdvisorProviderProvider.getIfAvailable();

        if (aiGuardrailsAdvisorProvider == null) {
            return null;
        }

        return aiGuardrailsAdvisorProvider.getMetrics(null, null, GUARDRAILS_SURFACE);
    }
}
