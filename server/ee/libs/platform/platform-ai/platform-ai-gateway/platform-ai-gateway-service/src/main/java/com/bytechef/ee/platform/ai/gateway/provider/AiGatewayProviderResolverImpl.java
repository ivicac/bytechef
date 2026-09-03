/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.provider;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * @version ee
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
@SuppressFBWarnings("EI")
class AiGatewayProviderResolverImpl implements AiGatewayProviderResolver {

    private final AiGatewayProviderService aiGatewayProviderService;

    AiGatewayProviderResolverImpl(AiGatewayProviderService aiGatewayProviderService) {
        this.aiGatewayProviderService = aiGatewayProviderService;
    }

    @Override
    public AiGatewayProvider resolve(long connectedUserId, AiGatewayProviderType type) {
        Optional<AiGatewayProvider> connectedUserProvider =
            aiGatewayProviderService.fetchProviderByConnectedUserIdAndType(connectedUserId, type);

        return connectedUserProvider.filter(AiGatewayProvider::isEnabled)
            .orElseGet(() -> resolveTenantProvider(type));
    }

    @Override
    public AiGatewayProvider resolveTenantProvider(AiGatewayProviderType type) {
        List<AiGatewayProvider> enabledProviders = aiGatewayProviderService.getEnabledProviders();

        // Positive allowlist: only a row bound to NO connected user is a legitimate shared/tenant fallback. Matching
        // on type alone (without this filter) would let a DIFFERENT connected user's own BYOK credential serve as the
        // "shared" provider for everyone else's traffic — a cross-tenant credential leak, not a convenience.
        return enabledProviders.stream()
            .filter(provider -> provider.getConnectedUserId() == null)
            .filter(provider -> provider.getType() == type)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No enabled provider found for type: " + type));
    }
}
