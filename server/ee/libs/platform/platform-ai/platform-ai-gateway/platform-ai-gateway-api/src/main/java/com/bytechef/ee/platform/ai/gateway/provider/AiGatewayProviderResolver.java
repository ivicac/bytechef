/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.provider;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;

/**
 * Resolves the {@link AiGatewayProvider} to use for a connected user's traffic of a given provider type, implementing
 * BYOK (bring-your-own-key): a connected user's own provider of that type — if any, and enabled — wins over the
 * tenant's shared provider of the same type.
 *
 * <p>
 * The resolved {@link AiGatewayProvider} is handed to the same {@code AiGatewayChatModelFactory} /
 * {@code AiGatewayEmbeddingModelFactory} that a tenant-owned provider goes through, whichever it turns out to be — this
 * resolver never builds a model client itself. That keeps the SSRF guard
 * ({@code AiObservabilityUrlValidator#validateExternalUrl}) and the API-key decryption those factories perform as the
 * single path both kinds of provider go through, so a customer-supplied {@code baseUrl} — strictly more hostile input
 * than a tenant-supplied one — can never bypass them.
 *
 * @version ee
 */
public interface AiGatewayProviderResolver {

    /**
     * Resolves the provider to use for {@code connectedUserId} and {@code type}.
     *
     * <p>
     * Precedence is customer-then-tenant: the connected user's own provider of {@code type} wins when one exists and is
     * enabled. A connected-user provider that exists but is disabled falls through to the tenant's provider rather than
     * failing the request — the same choice {@code AiGatewayRoutingPolicyService}'s connected-user precedence level
     * makes for a disabled routing policy, so an operator turning off one BYOK credential degrades to the vendor's own
     * credential rather than breaking the customer's traffic outright.
     *
     * @throws IllegalArgumentException if neither the connected user nor the tenant has an enabled provider of
     *                                  {@code type}
     */
    AiGatewayProvider resolve(long connectedUserId, AiGatewayProviderType type);

    /**
     * Returns the tenant's shared provider of {@code type}: an enabled provider bound to no connected user.
     *
     * <p>
     * This is the provider a request uses with no BYOK override in play, and it is the fallback {@link #resolve} falls
     * back to. It is exposed separately for callers that already know a request has no connected user (automation
     * traffic) or that need to pick a provider purely by type with no connected user in the picture at all — the
     * "provider/model" direct-routing identifier in {@code AiGatewayFacadeImpl#resolveModel} is exactly this shape: it
     * names a type, not a specific provider id, so it has no already-known tenant provider to start from the way the
     * by-id (model-deployment) paths do.
     *
     * <p>
     * Deliberately excludes every connected-user-scoped row, even though {@code AiGatewayProviderService
     * #getEnabledProviders()} returns them too: falling back to any enabled provider of the requested type, without
     * this filter, would let one connected user's own BYOK credential serve as the "shared" fallback for a completely
     * different connected user's traffic — a cross-tenant credential leak.
     *
     * @throws IllegalArgumentException if no enabled, non-connected-user-scoped provider of {@code type} exists
     */
    AiGatewayProvider resolveTenantProvider(AiGatewayProviderType type);
}
