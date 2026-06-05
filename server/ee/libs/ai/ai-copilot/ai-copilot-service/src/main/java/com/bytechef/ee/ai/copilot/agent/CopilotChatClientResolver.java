/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.agent;

import com.agui.core.state.State;
import com.bytechef.ee.automation.ai.gateway.domain.WorkspaceAiGatewayProvider;
import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.catalog.CatalogChatClientResolver;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayChatModelFactory;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Resolves a per-request override {@link ChatClient} for Copilot conversations that carry a user-selected (provider,
 * model) pair, supplied by the chat-toolbar picker via AG-UI state keys {@code userSelectedLlmProvider} +
 * {@code userSelectedLlmModel}. Unlike the AI Hub sibling, Copilot has no per-agent override layer — it's a single
 * precedence: user-selected, else workspace default.
 *
 * <p>
 * Looks up the workspace's matching {@link AiGatewayProvider}, gets a {@link ChatModel} from
 * {@link AiGatewayChatModelFactory}, and builds a {@link ChatClient} with the model name set via {@link ChatOptions}.
 *
 * <p>
 * Returns {@code null} (fall back to the workspace {@code @Primary ChatModel}) on any of:
 * <ul>
 * <li>Either state key absent — common case, no override requested.</li>
 * <li>State key {@code workspaceId} absent or unparseable — defensive; Copilot's controller doesn't inject a verified
 * workspaceId today, so the resolver reads the client-supplied value. Because Copilot doesn't perform tool-level data
 * access against the workspace (it's a UI assistant, not an automation runner), an attacker passing a forged
 * workspaceId would at worst get a model from a workspace they have AI Gateway visibility into — which is the same
 * surface as the Personal Agent form's provider/model dropdowns, just exercised via state instead of the form.</li>
 * <li>The user-selected provider is not enabled in the workspace (honors user intent: revert to workspace default
 * rather than picking something the user didn't ask for).</li>
 * </ul>
 *
 * <p>
 * Half-set user state (only provider or only model present) returns {@code null} directly — Copilot has no second
 * precedence layer to fall through to, so the workspace default is the right floor. A single warning log fires so
 * persistent half-set states surface to operators.
 *
 * <p>
 * Gated by {@code bytechef.ai.gateway.enabled=true} — the AI Gateway services are absent in CE and lightweight EE
 * variants. When the bean is absent, the Copilot agents silently skip the resolver entirely (via the optional
 * {@code ObjectProvider} injection in {@code CopilotConfiguration}) and use their builder-time default ChatClient.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class CopilotChatClientResolver implements OverrideChatClientResolver {

    private static final Logger log = LoggerFactory.getLogger(CopilotChatClientResolver.class);

    /**
     * AG-UI state key for the user-selected LLM provider. Kept as a raw string here (instead of a constants class)
     * because Copilot has no centralized {@code StateKeys} holder today. The AI Hub side uses the same wire format —
     * see {@code AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY}.
     */
    static final String USER_SELECTED_LLM_PROVIDER_KEY = "userSelectedLlmProvider";

    /**
     * AG-UI state key for the user-selected LLM model. See {@link #USER_SELECTED_LLM_PROVIDER_KEY}.
     */
    static final String USER_SELECTED_LLM_MODEL_KEY = "userSelectedLlmModel";

    /**
     * AG-UI state key for the workspace id. The Copilot client populates this from the active workspace; the resolver
     * uses it to look up which providers are enabled in that workspace.
     */
    static final String WORKSPACE_ID_KEY = "workspaceId";

    /**
     * AG-UI state key for the active environment id (client-supplied). Used to resolve the platform AI provider catalog
     * API key for the chosen provider.
     */
    static final String ENVIRONMENT_ID_KEY = "environmentId";

    private final WorkspaceAiGatewayProviderService workspaceAiGatewayProviderService;
    private final AiGatewayProviderService aiGatewayProviderService;
    private final AiGatewayChatModelFactory aiGatewayChatModelFactory;
    private final CatalogChatClientResolver catalogChatClientResolver;

    @SuppressFBWarnings("EI")
    public CopilotChatClientResolver(
        WorkspaceAiGatewayProviderService workspaceAiGatewayProviderService,
        AiGatewayProviderService aiGatewayProviderService, AiGatewayChatModelFactory aiGatewayChatModelFactory,
        CatalogChatClientResolver catalogChatClientResolver) {

        this.workspaceAiGatewayProviderService = workspaceAiGatewayProviderService;
        this.aiGatewayProviderService = aiGatewayProviderService;
        this.aiGatewayChatModelFactory = aiGatewayChatModelFactory;
        this.catalogChatClientResolver = catalogChatClientResolver;
    }

    @Override
    public @Nullable ChatClient resolve(State state) {
        if (state == null) {
            return null;
        }

        String llmProvider = asString(state.get(USER_SELECTED_LLM_PROVIDER_KEY));
        String llmModel = asString(state.get(USER_SELECTED_LLM_MODEL_KEY));

        if (llmProvider == null || llmModel == null) {
            if ((llmProvider == null) != (llmModel == null)) {
                log.warn(
                    "Copilot user-selected LLM half-set (provider={}, model={}); falling back to workspace default",
                    llmProvider, llmModel);
            }

            return null;
        }

        // Environment is a deployment-wide enum (DEVELOPMENT/STAGING/PRODUCTION), not a per-user ACL, so there's no
        // membership check to add here. The shared CatalogChatClientResolver range-validates the ordinal and fails
        // closed on garbage/out-of-range values. Copilot is a read-only UI assistant that already trusts the
        // client-supplied workspaceId by design (see class Javadoc), so no verified-state injection is added here.
        Long environment = asLong(state.get(ENVIRONMENT_ID_KEY));

        if (environment != null) {
            ChatClient catalogChatClient = catalogChatClientResolver.resolve(
                environment.intValue(), llmProvider, llmModel);

            if (catalogChatClient != null) {
                return catalogChatClient;
            }
        }

        Long workspaceId = asLong(state.get(WORKSPACE_ID_KEY));

        if (workspaceId == null) {
            log.warn(
                "Copilot user-selected LLM override skipped: state has no parseable workspaceId. Falling back to "
                    + "workspace default for this turn.");

            return null;
        }

        AiGatewayProvider provider = resolveProvider(workspaceId, llmProvider);

        if (provider == null) {
            log.warn(
                "Copilot user-selected LLM override skipped: workspace {} has no enabled provider matching '{}'. "
                    + "Falling back to workspace default for this turn.",
                workspaceId, llmProvider);

            return null;
        }

        ChatModel chatModel = aiGatewayChatModelFactory.getChatModel(provider);

        // ChatClient.Builder#defaultOptions takes a ChatOptions.Builder (NOT a built ChatOptions). Each
        // ChatClient.builder() build is cheap; the heavy work is in the underlying ChatModel construction which the
        // factory caches.
        return ChatClient.builder(chatModel)
            .defaultOptions(
                ChatOptions.builder()
                    .model(llmModel))
            .build();
    }

    private @Nullable AiGatewayProvider resolveProvider(long workspaceId, String llmProvider) {
        for (WorkspaceAiGatewayProvider workspaceProvider : workspaceAiGatewayProviderService
            .getWorkspaceProviders(workspaceId)) {

            AiGatewayProvider provider = aiGatewayProviderService.getProvider(workspaceProvider.getProviderId());

            if (provider == null || !provider.isEnabled()) {
                continue;
            }

            // Match on provider type name (case-insensitive). The client stores the provider type as a string (e.g.
            // "OPENAI", "ANTHROPIC"), which corresponds to AiGatewayProviderType.name().
            if (provider.getType()
                .name()
                .equalsIgnoreCase(llmProvider)) {

                return provider;
            }
        }

        return null;
    }

    private static @Nullable String asString(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    private static @Nullable Long asLong(@Nullable Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
