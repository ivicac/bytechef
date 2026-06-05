/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.personalagent;

import com.bytechef.ee.automation.ai.gateway.domain.WorkspaceAiGatewayProvider;
import com.bytechef.ee.automation.ai.gateway.facade.WorkspaceAiGatewayModelFacade;
import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModel;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Save-time validator for the per-agent {@code (llmProvider, llmModel)} pair. Invoked from
 * {@link AiHubPersonalAgentServiceImpl#create} and {@link AiHubPersonalAgentServiceImpl#update} when both fields are
 * non-null. Pre-validation at save avoids the worse failure mode where an admin saves a misconfigured agent and only
 * discovers at the next voice / chat turn that the override silently fell back to the workspace default.
 *
 * <p>
 * Separated from {@code AiHubChatClientResolver} (which handles runtime ChatClient construction) by the v1.1 class
 * split: the resolver runs on every chat turn and only cares about provider enablement; this validator also has to
 * check that the requested model name is enabled under the provider, so it pulls in
 * {@link WorkspaceAiGatewayModelFacade}. Keeping the two concerns in one class would have meant either the resolver
 * carried the model facade it doesn't use, or this validator hid inside a class named after the runtime path.
 *
 * <p>
 * Gated by {@code bytechef.ai.gateway.enabled=true} — the AI Gateway services are absent in CE and lightweight EE
 * variants. When the bean is absent, the service skips validation (trusts the persisted values; the runtime resolver's
 * warn-and-fallback path handles any subsequent mismatch).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class PersonalAgentSaveValidator {

    private final WorkspaceAiGatewayProviderService workspaceAiGatewayProviderService;
    private final WorkspaceAiGatewayModelFacade workspaceAiGatewayModelFacade;
    private final AiGatewayProviderService aiGatewayProviderService;

    @SuppressFBWarnings("EI")
    public PersonalAgentSaveValidator(
        WorkspaceAiGatewayProviderService workspaceAiGatewayProviderService,
        WorkspaceAiGatewayModelFacade workspaceAiGatewayModelFacade,
        AiGatewayProviderService aiGatewayProviderService) {

        this.workspaceAiGatewayProviderService = workspaceAiGatewayProviderService;
        this.workspaceAiGatewayModelFacade = workspaceAiGatewayModelFacade;
        this.aiGatewayProviderService = aiGatewayProviderService;
    }

    /**
     * Validates the per-agent (provider, model) pair. Throws {@link IllegalArgumentException} when:
     *
     * <ul>
     * <li>the workspace has no enabled provider matching the given {@code llmProvider} type, OR</li>
     * <li>the workspace has no enabled model with the given {@code llmModel} name under that provider.</li>
     * </ul>
     *
     * Both messages embed the offending value so the caller's GraphQL surface returns an actionable error rather than a
     * generic "validation failed."
     */
    public void validate(long workspaceId, String llmProvider, String llmModel) {
        // Catalog-based selections (provider keys like "ai.provider.openAi", emitted by the catalog ModelPicker) are
        // resolved at runtime from the platform AI provider catalog (CatalogChatClientResolver), NOT the workspace AI
        // Gateway, so the gateway provider/model checks below don't apply. Gateway type names ("openai", "anthropic")
        // never carry this prefix, so this guard only short-circuits catalog selections. Runtime degrades to the
        // workspace default if the catalog can't resolve the pair.
        if (llmProvider.startsWith("ai.provider.")) {
            return;
        }

        AiGatewayProvider provider = resolveProvider(workspaceId, llmProvider);

        if (provider == null) {
            throw new IllegalArgumentException(
                "LLM provider '" + llmProvider + "' is not enabled in this workspace. Enable it in AI Gateway " +
                    "settings before assigning it to a personal agent.");
        }

        boolean modelFound = false;

        for (AiGatewayModel model : workspaceAiGatewayModelFacade.getWorkspaceModels(workspaceId)) {
            if (model == null || !model.isEnabled()) {
                continue;
            }

            if (Objects.equals(model.getProviderId(), provider.getId()) && llmModel.equals(model.getName())) {
                modelFound = true;

                break;
            }
        }

        if (!modelFound) {
            throw new IllegalArgumentException(
                "LLM model '" + llmModel + "' is not enabled in this workspace under provider '" + llmProvider +
                    "'. Enable it in AI Gateway settings before assigning it to a personal agent.");
        }
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
}
