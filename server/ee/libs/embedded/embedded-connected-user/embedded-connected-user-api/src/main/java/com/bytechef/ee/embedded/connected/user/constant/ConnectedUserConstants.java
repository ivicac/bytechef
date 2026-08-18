/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.constant;

import java.util.Set;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class ConnectedUserConstants {

    /**
     * Literal first path segments of every "Frontend" (no-{@code {externalUserId}}) route mounted under
     * {@code /api/embedded/v<n>/}, traceable to their {@code openapi.yaml}:
     * <ul>
     * <li>{@code automation}, {@code me}, {@code components}, {@code connections}, {@code integration-instances},
     * {@code integrations} -- embedded-configuration-public-rest</li>
     * <li>{@code app-events}, {@code workflows} -- embedded-webhook-public-rest</li>
     * <li>{@code unified} -- embedded-unified-rest ({@code /v1/unified/accounting/**}, {@code /v1/unified/crm/**})</li>
     * </ul>
     * This is a maintained allowlist, not a route-agnostic derivation -- it must be kept in sync by hand when a new
     * no-{@code {externalUserId}} route is added under {@code /api/embedded/v<n>/}.
     *
     * <p>
     * {@code external} is a RETIRED literal: the MCP integration-instance controllers briefly mounted routes under
     * {@code /external/{externalUserId}/...}, letting the converter capture the literal {@code external} as a user id
     * and mint a phantom connected user. The routes are normalized to the bare {@code /{externalUserId}/} prefix, and
     * the segment stays reserved so a stale caller of the old URL is rejected at authentication instead of minting.
     */
    public static final Set<String> FRONTEND_RESERVED_PATH_SEGMENTS = Set.of(
        "app-events", "automation", "components", "connections", "external", "integration-instances", "integrations",
        "me", "unified", "workflows");

    private ConnectedUserConstants() {
    }
}
