/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings.BlockingMode;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService;
import com.bytechef.graphql.error.GraphQlBadRequestException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL controller exposing {@link AiGuardrailsWorkspaceSettings} for the admin settings UI.
 *
 * <p>
 * The read query is scoped to workspace members (or a tenant admin): a non-null {@code workspaceId} is checked via the
 * {@code AI_GATEWAY_VIEW} workspace permission scope (the same scope every other AI-settings read facade reuses -- see
 * {@code AiGatewayWorkspaceSettingsFacadeImpl}, {@code AiPromptFacadeImpl}); the null-{@code workspaceId}
 * tenant-default read is admin-only, since it has no workspace to scope against.
 *
 * @version ee
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnCoordinator
class AiGuardrailsWorkspaceSettingsGraphQlController {

    private final AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService;

    @SuppressFBWarnings("EI")
    AiGuardrailsWorkspaceSettingsGraphQlController(
        AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService) {

        this.aiGuardrailsWorkspaceSettingsService = aiGuardrailsWorkspaceSettingsService;
    }

    /**
     * The {@code scope} condition is load-bearing, not defensive. This gate keys on {@code workspaceId} while the body
     * below dispatches on {@code scope}, so without it a caller passing their <em>own</em> workspace id together with
     * {@code scope: EMBEDDED} satisfied the membership check and was then handed the tenant-wide embedded row — a row
     * whose writer requires {@code ROLE_ADMIN}. Any argument the body branches on has to appear here too, or the gate
     * is authorizing a different request than the one that runs.
     */
    @QueryMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or (#scope != "
        + "T(com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope).EMBEDDED "
        + "&& #workspaceId != null && hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_VIEW'))")
    public @Nullable AiGuardrailsWorkspaceSettings aiGuardrailsWorkspaceSettings(
        @Argument @Nullable Long workspaceId, @Argument @Nullable AiGuardrailsSettingsScope scope) {

        if (scope == AiGuardrailsSettingsScope.EMBEDDED) {
            return aiGuardrailsWorkspaceSettingsService.fetchEmbeddedSettings()
                .orElse(null);
        }

        return aiGuardrailsWorkspaceSettingsService.fetchSettings(workspaceId)
            .orElse(null);
    }

    /**
     * See {@link #aiGuardrailsWorkspaceSettings}'s Javadoc for why any argument the body branches on has to appear here
     * too: this gate keys on {@code input.workspaceId} and {@code input.scope} while the body below dispatches on
     * {@code scopeOf(input)}.
     *
     * <p>
     * The non-admin branch names the scopes it <em>allows</em> ({@code null} or explicit {@code WORKSPACE}) rather than
     * the one it forbids ({@code EMBEDDED}). A deny-list here would authorize {@code scope: PLATFORM} too --
     * {@code PLATFORM != EMBEDDED} is true, so a workspace admin sending {@code {scope: PLATFORM, workspaceId: 7}}
     * would pass this gate and reach the body, which happens to refuse that combination only because
     * {@code validateScopeWorkspaceIdPairing} rejects a non-null {@code workspaceId} paired with {@code PLATFORM}. That
     * is exactly the anti-pattern this method must not repeat: authorization backstopped by a body-side validation
     * instead of enforced at the gate. An allow-list has no such gap, because it has to be told about a new
     * non-workspace scope before it lets anything through for it -- an oversight fails closed instead of open.
     */
    @MutationMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or ((#input.scope == null || #input.scope == "
        + "T(com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope).WORKSPACE) "
        + "&& #input.workspaceId != null "
        + "&& hasPermission(#input.workspaceId, 'Workspace', 'AI_GATEWAY_EDIT'))")
    public AiGuardrailsWorkspaceSettings updateAiGuardrailsWorkspaceSettings(
        @Argument AiGuardrailsWorkspaceSettingsInput input) {

        AiGuardrailsSettingsScope scope = scopeOf(input);

        validateScopeWorkspaceIdPairing(scope, input.workspaceId());

        return aiGuardrailsWorkspaceSettingsService.saveSettings(new AiGuardrailsWorkspaceSettings(
            scope, input.workspaceId(), input.redactPii(), input.redactSecrets(), input.blockedTerms(),
            input.moderationEnabled(), input.injectionDetectionEnabled(), input.scanResponses(),
            input.blockingMode(), input.minConfidence(), input.redactMcpResults(),
            input.restoreIntoWorkflowOutput()));
    }

    private AiGuardrailsSettingsScope scopeOf(AiGuardrailsWorkspaceSettingsInput input) {
        if (input.scope() != null) {
            return input.scope();
        }

        return input.workspaceId() == null ? AiGuardrailsSettingsScope.PLATFORM : AiGuardrailsSettingsScope.WORKSPACE;
    }

    // Mirrors AiGuardrailsWorkspaceSettings's own compact-constructor invariant so a mismatched pair fails here,
    // as a client-input GraphQlBadRequestException, instead of reaching the record and surfacing as an opaque,
    // unmapped IllegalArgumentException (INTERNAL_ERROR). The record's check stays in place as the last line of
    // defence; this one exists purely to give the caller a clean, actionable error.
    private void validateScopeWorkspaceIdPairing(AiGuardrailsSettingsScope scope, @Nullable Long workspaceId) {
        if ((scope == AiGuardrailsSettingsScope.WORKSPACE) != (workspaceId != null)) {
            throw new GraphQlBadRequestException(
                "workspaceId must be non-null exactly when scope is WORKSPACE, got scope=%s, workspaceId=%s"
                    .formatted(scope, workspaceId));
        }
    }

    public record AiGuardrailsWorkspaceSettingsInput(
        @Nullable AiGuardrailsSettingsScope scope, @Nullable Long workspaceId, @Nullable Boolean redactPii,
        @Nullable Boolean redactSecrets, @Nullable String blockedTerms, @Nullable Boolean moderationEnabled,
        @Nullable Boolean injectionDetectionEnabled, @Nullable Boolean scanResponses,
        @Nullable BlockingMode blockingMode, @Nullable Double minConfidence,
        @Nullable Boolean redactMcpResults, @Nullable Boolean restoreIntoWorkflowOutput) {
    }
}
