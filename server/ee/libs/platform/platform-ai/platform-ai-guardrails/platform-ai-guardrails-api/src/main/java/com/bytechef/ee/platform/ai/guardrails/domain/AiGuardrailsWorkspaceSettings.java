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
 * store already handles scope/audit/versioning/encryption and this is plain config data. {@link #scope()} is required;
 * {@link #workspaceId()} is non-null exactly when the scope is {@code WORKSPACE}; every other field is nullable, and a
 * null one means "not overridden at this level". For a real workspace (non-null {@code workspaceId}) that unions the
 * field with the GLOBAL {@code bytechef.ai.gateway.guardrails.*} properties only — it does NOT fall back to the
 * tenant-default (null-{@code workspaceId}) row's value; the two PLATFORM-scoped rows are otherwise independent. The
 * tenant-default row is consulted only for calls that resolve to {@code workspaceId == null} in the first place (e.g.
 * embedded runs, unattributed calls). See {@code AiGuardrailsWorkspaceSettingsServiceImpl}'s class javadoc for how the
 * tenant-default row is stored, and {@code AiGuardrails#resolvePolicy} for the union logic.
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
 * <p>
 * {@code redactMcpResults} is deliberately a separate switch from {@code redactPii}/{@code redactSecrets} rather than
 * something they imply. An MCP tool is frequently how a customer hands data to their own agent on purpose; enabling
 * guardrails for chat surfaces must not silently start returning {@code [REDACTED_EMAIL_ADDRESS]} into a working MCP
 * pipeline. It selects the surface; the categories and threshold still come from the fields above.
 * </p>
 *
 * @version ee
 */
public record AiGuardrailsWorkspaceSettings(
    AiGuardrailsSettingsScope scope,
    Long workspaceId, // null for PLATFORM (tenant default) and EMBEDDED alike; non-null only when scope is WORKSPACE
    Boolean redactPii,
    Boolean redactSecrets,
    String blockedTerms, // comma-separated, same format as the gateway field
    Boolean moderationEnabled,
    Boolean injectionDetectionEnabled,
    Boolean scanResponses,
    BlockingMode blockingMode,
    Double minConfidence, // null = use SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE
    Boolean redactMcpResults) { // null = not set at this level = off

    public static final String PROPERTY_KEY = "ai_guardrails_workspace_settings";

    public AiGuardrailsWorkspaceSettings {
        if (minConfidence != null && (!Double.isFinite(minConfidence) || minConfidence < 0.0 || minConfidence > 1.0)) {
            throw new IllegalArgumentException("minConfidence must be null or between 0.0 and 1.0, got: " +
                minConfidence);
        }

        // A null scope satisfies the pairing check below whenever workspaceId is also null, so without this the
        // record is constructible in a shape that NPEs later, in the service's scopeOf switch rather than here.
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }

        if ((scope == AiGuardrailsSettingsScope.WORKSPACE) != (workspaceId != null)) {
            throw new IllegalArgumentException(
                "workspaceId must be non-null exactly when scope is WORKSPACE, got scope=" + scope +
                    ", workspaceId=" + workspaceId);
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
