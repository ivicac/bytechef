/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.agent;

import com.agui.core.state.State;
import com.bytechef.ee.automation.ai.gateway.domain.WorkspaceAiGatewayProvider;
import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.catalog.CatalogChatClientResolver;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayChatModelFactory;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.ee.platform.aihub.agent.AiHubSpringAIAgent;
import com.bytechef.ee.platform.aihub.util.AiHubStateKeys;
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
 * Resolves a per-request override {@link ChatClient} for AI Hub conversations that carry a model override. Honors two
 * precedence layers, in order:
 *
 * <ol>
 * <li><b>User-selected, per conversation</b> — {@link AiHubStateKeys#USER_SELECTED_LLM_PROVIDER_KEY} +
 * {@link AiHubStateKeys#USER_SELECTED_LLM_MODEL_KEY}, set by the client (chat-toolbar picker).</li>
 * <li><b>Personal-agent override, per agent</b> — {@link AiHubStateKeys#PERSONAL_AGENT_LLM_PROVIDER_KEY} +
 * {@link AiHubStateKeys#PERSONAL_AGENT_LLM_MODEL_KEY}, injected by {@code AiHubRoutingAgent} when the active personal
 * agent has the override columns populated.</li>
 * </ol>
 *
 * <p>
 * Looks up the workspace's matching {@link AiGatewayProvider}, gets a {@link ChatModel} from
 * {@link AiGatewayChatModelFactory}, and builds a {@link ChatClient} with the model name set via {@link ChatOptions}.
 *
 * <p>
 * Returns {@code null} (fall back to default — caller uses the workspace {@code @Primary ChatModel}) on any of:
 * <ul>
 * <li>Neither layer fully set (no override requested — common case).</li>
 * <li>{@link AiHubStateKeys#VERIFIED_WORKSPACE_ID} absent (defensive).</li>
 * <li>The user-selected provider is not enabled in the workspace (honors user intent: don't silently fall back to the
 * personal-agent override they just stepped away from — instead, revert to the workspace floor).</li>
 * <li>Workspace has no enabled provider matching the resolved layer's requested type.</li>
 * </ul>
 *
 * <p>
 * Half-set states (only one of provider/model present) at the user-selected layer fall through to the personal-agent
 * layer with a single warning log — half-set is treated as a transient client artifact (e.g. user mid-picking), not
 * malicious input, so we don't 400 the turn.
 *
 * <p>
 * Save-time validation for personal-agent override pairs lives in {@link PersonalAgentSaveValidator}, NOT here — the
 * class split keeps the runtime resolver focused on per-turn ChatClient construction and routes admin save-path
 * concerns to a dedicated bean. Both classes share the same enabled-provider lookup pattern but stay decoupled so the
 * runtime path doesn't accidentally pick up validation-only dependencies (e.g. {@code WorkspaceAiGatewayModelFacade}).
 *
 * <p>
 * Gated by {@code bytechef.ai.gateway.enabled=true} — the AI Gateway services are absent in CE and lightweight EE
 * variants. When the bean is absent, {@link AiHubSpringAIAgent} silently skips the resolver entirely and uses its
 * builder-time default ChatClient (workspace default LLM).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class AiHubChatClientResolver implements AiHubSpringAIAgent.OverrideChatClientResolver {

    private static final Logger log = LoggerFactory.getLogger(AiHubChatClientResolver.class);

    private final WorkspaceAiGatewayProviderService workspaceAiGatewayProviderService;
    private final AiGatewayProviderService aiGatewayProviderService;
    private final AiGatewayChatModelFactory aiGatewayChatModelFactory;
    private final CatalogChatClientResolver catalogChatClientResolver;

    @SuppressFBWarnings("EI")
    public AiHubChatClientResolver(
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

        Long workspaceId = asLong(state.get(AiHubStateKeys.VERIFIED_WORKSPACE_ID));

        if (workspaceId == null) {
            return null;
        }

        // Precedence: user-selected (chat-toolbar picker) wins over personal-agent (configured at agent save).
        String llmProvider = asString(state.get(AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY));
        String llmModel = asString(state.get(AiHubStateKeys.USER_SELECTED_LLM_MODEL_KEY));

        boolean userSelected = llmProvider != null && llmModel != null;

        if (!userSelected) {
            if ((llmProvider == null) != (llmModel == null)) {
                // Transient client artifact (e.g. user mid-picking): one half arrived, the other didn't. Don't 400
                // the turn — fall through to the next precedence layer so the conversation continues, and log once
                // so the asymmetry is visible if it persists across turns.
                log.warn(
                    "User-selected LLM half-set (provider={}, model={}); falling through to personal-agent override",
                    llmProvider, llmModel);
            }

            llmProvider = asString(state.get(AiHubStateKeys.PERSONAL_AGENT_LLM_PROVIDER_KEY));
            llmModel = asString(state.get(AiHubStateKeys.PERSONAL_AGENT_LLM_MODEL_KEY));
        }

        if (llmProvider == null || llmModel == null) {
            return null;
        }

        Integer environment = asInteger(state.get(AiHubStateKeys.ENVIRONMENT_ID));

        if (environment != null) {
            ChatClient catalogChatClient = catalogChatClientResolver.resolve(environment, llmProvider, llmModel);

            if (catalogChatClient != null) {
                return catalogChatClient;
            }
        }

        AiGatewayProvider provider = resolveProvider(workspaceId, llmProvider);

        if (provider == null) {
            // Two sub-cases that surface here:
            // 1. User explicitly picked a provider the workspace no longer has enabled (admin disabled it
            // mid-conversation, or stale client cache after AI Providers settings change).
            // 2. Admin set a per-agent override against a provider the workspace doesn't have (e.g. the admin
            // deleted the provider after assigning it to an agent).
            // Both fall back to workspace default with a warning. The user-selected case is logged distinctly so
            // operators can tell apart "user picked something stale" from "admin misconfigured an agent".
            if (userSelected) {
                log.warn(
                    "User-selected LLM override skipped: workspace {} has no enabled provider matching '{}'. " +
                        "Falling back to workspace default for this turn.",
                    workspaceId, llmProvider);
            } else {
                log.warn(
                    "Personal-agent LLM override skipped: workspace {} has no enabled provider matching '{}'. " +
                        "Falling back to workspace default. Re-save the agent with a different provider to fix.",
                    workspaceId, llmProvider);
            }

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

            // Match on provider type name (case-insensitive). The agent stores the provider type as a string (e.g.
            // "openai", "anthropic"), which corresponds to AiGatewayProviderType.name() lowercase.
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

    private static @Nullable Integer asInteger(@Nullable Object value) {
        Long parsed = asLong(value);

        return parsed == null ? null : parsed.intValue();
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
