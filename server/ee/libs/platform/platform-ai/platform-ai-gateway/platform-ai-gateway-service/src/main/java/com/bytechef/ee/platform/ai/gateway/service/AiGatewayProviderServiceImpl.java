/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.service;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.domain.ApiKey;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayChatModelFactory;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayEmbeddingModelFactory;
import com.bytechef.ee.platform.ai.gateway.repository.AiGatewayProviderRepository;
import com.bytechef.ee.platform.ai.model.catalog.domain.AiModel;
import com.bytechef.ee.platform.ai.model.catalog.service.AiModelService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @version ee
 */
@Service
@Transactional
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
@SuppressFBWarnings("EI")
class AiGatewayProviderServiceImpl implements AiGatewayProviderService {

    private final AiGatewayChatModelFactory aiGatewayChatModelFactory;
    private final AiGatewayEmbeddingModelFactory aiGatewayEmbeddingModelFactory;
    private final AiModelService aiModelService;
    private final AiGatewayProviderRepository aiGatewayProviderRepository;

    public AiGatewayProviderServiceImpl(
        AiGatewayChatModelFactory aiGatewayChatModelFactory,
        AiGatewayEmbeddingModelFactory aiGatewayEmbeddingModelFactory,
        AiModelService aiModelService,
        AiGatewayProviderRepository aiGatewayProviderRepository) {

        this.aiGatewayChatModelFactory = aiGatewayChatModelFactory;
        this.aiGatewayEmbeddingModelFactory = aiGatewayEmbeddingModelFactory;
        this.aiModelService = aiModelService;
        this.aiGatewayProviderRepository = aiGatewayProviderRepository;
    }

    @Override
    public AiGatewayProvider create(AiGatewayProvider provider) {
        Validate.notNull(provider, "'provider' must not be null");
        Validate.isTrue(provider.getId() == null, "'id' must be null");

        return aiGatewayProviderRepository.save(provider);
    }

    @Override
    public void delete(long id) {
        aiGatewayChatModelFactory.evict(id);
        aiGatewayEmbeddingModelFactory.evict(id);

        List<AiModel> models = aiModelService.getModelsByProviderId(id);

        for (AiModel model : models) {
            aiModelService.delete(model.getId());
        }

        aiGatewayProviderRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiGatewayProvider> fetchProviderByConnectedUserIdAndType(
        long connectedUserId, AiGatewayProviderType type) {

        return aiGatewayProviderRepository.findByConnectedUserIdAndType(connectedUserId, type.ordinal());
    }

    @Override
    @Transactional(readOnly = true)
    public AiGatewayProvider getProvider(long id) {
        return aiGatewayProviderRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiGatewayProvider> getProviders(Collection<Long> ids) {
        return aiGatewayProviderRepository.findAllById(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiGatewayProvider> getProviders() {
        return aiGatewayProviderRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiGatewayProvider> getEnabledProviders() {
        return aiGatewayProviderRepository.findAllByEnabled(true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiGatewayProvider> getProvidersByWorkspaceId(long workspaceId) {
        return aiGatewayProviderRepository.findAllByWorkspaceId(workspaceId);
    }

    @Override
    public AiGatewayProvider update(AiGatewayProvider provider) {
        Validate.notNull(provider, "'provider' must not be null");

        AiGatewayProvider existingProvider = getProvider(provider.getId());

        existingProvider.setName(provider.getName());
        existingProvider.setBaseUrl(provider.getBaseUrl());
        existingProvider.setConfig(provider.getConfig());
        existingProvider.setEnabled(provider.isEnabled());

        String incomingApiKey = provider.revealApiKey();

        if (incomingApiKey != null && !incomingApiKey.isEmpty()) {
            existingProvider.setApiKey(ApiKey.of(incomingApiKey));
        }

        AiGatewayProvider savedProvider = aiGatewayProviderRepository.save(existingProvider);

        aiGatewayChatModelFactory.evict(existingProvider.getId());
        aiGatewayEmbeddingModelFactory.evict(existingProvider.getId());

        return savedProvider;
    }

    @Override
    public void updateConnectedUserId(long id, @Nullable Long connectedUserId) {
        AiGatewayProvider provider = getProvider(id);

        // ck_ai_gateway_provider_workspace_connected_user_not_both forbids both columns at once. A workspace-scoped
        // provider is not this method's to touch, so this is rejected here as a clear domain error rather than left
        // to surface as an unmapped DataIntegrityViolationException out of the save below. Mirrors the guard
        // AiGatewayRoutingPolicyService's connected-user binding facade already applies for its own workspace check
        // constraint -- placed here at the service method rather than only in a caller's facade, so whatever admin
        // surface is eventually built to bind BYOK providers to connected users inherits it rather than
        // reproducing it.
        if (connectedUserId != null && provider.getWorkspaceId() != null) {
            throw new IllegalArgumentException(
                "Provider " + id + " is bound to a workspace and cannot be bound to a connected user");
        }

        provider.setConnectedUserId(connectedUserId);

        aiGatewayProviderRepository.save(provider);
    }

    @Override
    public void updateWorkspaceId(long id, @Nullable Long workspaceId) {
        AiGatewayProvider provider = getProvider(id);

        provider.setWorkspaceId(workspaceId);

        aiGatewayProviderRepository.save(provider);
    }
}
