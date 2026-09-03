/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.domain;

import java.math.BigDecimal;

/**
 * Embedded-scope AI Gateway overrides. Persisted as a single
 * {@link com.bytechef.platform.configuration.domain.Property} row (scope={@code EMBEDDED}, scopeId={@code null},
 * key={@value #PROPERTY_KEY}). All fields are nullable; a null value means "inherit from the system default".
 *
 * <p>
 * The null {@code scopeId} is why this record depends on the partial unique index
 * {@code uk_property_key_scope_environment_null_scope_id} — Postgres treats every null as distinct, so the non-partial
 * constraint never fires for these rows.
 *
 * <p>
 * {@code defaultConnectedUserBudgetCap} is spec §7's per-connected-user cap: a USD spend ceiling
 * {@code AiGatewayFacadeImpl} checks against a connected user's rolling current-month spend, ahead of routing and the
 * LLM call, before every embedded chat completion. {@code null} keeps its "not set" meaning — every connected user in
 * the environment is uncapped, matching every other field on this record.
 *
 * @version ee
 */
public record AiGatewayEmbeddedSettings(
    Long environmentId,
    Integer retryCount,
    Integer timeoutMs,
    Boolean cacheEnabled,
    Integer cacheTtlSeconds,
    Integer logRetentionDays,
    Long defaultRoutingPolicyId,
    Integer softBudgetWarningPct,
    BigDecimal defaultConnectedUserBudgetCap) {

    public static final String PROPERTY_KEY = "ai_gateway_embedded_settings";

    public AiGatewayEmbeddedSettings {
        if (softBudgetWarningPct != null && (softBudgetWarningPct < 0 || softBudgetWarningPct > 100)) {
            throw new IllegalArgumentException(
                "softBudgetWarningPct must be between 0 and 100: " + softBudgetWarningPct);
        }

        if (defaultConnectedUserBudgetCap != null && defaultConnectedUserBudgetCap.signum() <= 0) {
            throw new IllegalArgumentException(
                "defaultConnectedUserBudgetCap must be positive: " + defaultConnectedUserBudgetCap);
        }
    }
}
