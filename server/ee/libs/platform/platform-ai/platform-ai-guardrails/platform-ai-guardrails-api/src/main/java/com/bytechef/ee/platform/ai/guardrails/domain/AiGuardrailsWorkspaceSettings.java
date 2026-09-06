/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.domain;

/**
 * Workspace-scoped AI guardrails configuration. Persisted as a single
 * {@link com.bytechef.platform.configuration.domain.Property} row rather than a dedicated table — the platform property
 * store already handles scope/audit/versioning/encryption and this is plain config data. All fields other than
 * {@link #workspaceId()} are nullable; a null field means "not overridden at this level". For a real workspace
 * (non-null {@code workspaceId}) that unions the field with the GLOBAL {@code bytechef.ai.gateway.guardrails.*}
 * properties only — it does NOT fall back to the tenant-default (null-{@code workspaceId}) row's value; the two
 * PLATFORM-scoped rows are otherwise independent. The tenant-default row is consulted only for calls that resolve to
 * {@code workspaceId == null} in the first place (e.g. embedded runs, unattributed calls). See
 * {@code AiGuardrailsWorkspaceSettingsServiceImpl}'s class javadoc for how the tenant-default row is stored, and
 * {@code AiGuardrails#resolvePolicy} for the union logic.
 *
 * <p>
 * {@link #minConfidence()} is an override, not a union member like the other fields: a {@code null} value means "use
 * {@code SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}", which keeps every workspace that predates this field behaving
 * exactly as before with no migration needed to populate it. Non-{@code null}, it is validated to {@code [0.0, 1.0]} in
 * the compact constructor below — the same range {@code PiiPattern}/{@code SecretPattern} already validate their own
 * {@code score} to — because this value feeds the identical {@code confidence() >= minConfidence} comparison (see
 * 2026-08-31 final-branch-review fix): unvalidated, {@code -1.0} would clear that comparison for every span regardless
 * of score, silently reinstating the exact bare-digit-run false-positive bug this whole feature exists to fix,
 * tenant-wide; {@code 1.5} would clear no span in the catalog, silently disabling PII and secret redaction entirely
 * while {@code redactPii}/{@code redactSecrets} keep reading {@code true} everywhere an operator would look.
 * </p>
 *
 * @version ee
 */
public record AiGuardrailsWorkspaceSettings(
    Long workspaceId, // null = tenant default
    Boolean redactPii,
    Boolean redactSecrets,
    String blockedTerms, // comma-separated, same format as the gateway field
    Boolean moderationEnabled,
    Boolean injectionDetectionEnabled,
    Boolean scanResponses,
    BlockingMode blockingMode,
    Double minConfidence) { // null = use SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE

    public static final String PROPERTY_KEY = "ai_guardrails_workspace_settings";

    public AiGuardrailsWorkspaceSettings {
        if (minConfidence != null && (!Double.isFinite(minConfidence) || minConfidence < 0.0 || minConfidence > 1.0)) {
            throw new IllegalArgumentException("minConfidence must be null or between 0.0 and 1.0, got: " +
                minConfidence);
        }
    }

    /**
     * Stored in the property value map by {@link #name()}, not ordinal — but the ordinal is still pinned (see
     * {@code BlockingModeStabilityTest}) so this enum stays append-only, matching every other persisted enum in this
     * codebase. Append new values at the end only.
     *
     * @version ee
     */
    public enum BlockingMode {

        BLOCK,
        REDACT_AND_CONTINUE
    }
}
