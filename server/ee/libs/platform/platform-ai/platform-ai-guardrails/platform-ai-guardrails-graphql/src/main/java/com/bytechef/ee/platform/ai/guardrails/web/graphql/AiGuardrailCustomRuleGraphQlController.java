/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailCustomRule;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailCustomRuleService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * Admin surface for a workspace's own detection rules.
 *
 * <p>
 * <b>This controller validates nothing.</b> That is deliberate: {@code AiGuardrailCustomRuleService} is the gate, so
 * every write path gets it rather than only this one. A validator the API remembers to call is a validator a future
 * importer or admin tool does not, and an unvalidated operator pattern is an unbounded loop on the request thread.
 * </p>
 *
 * <p>
 * Every operation takes {@code workspaceId} explicitly and the gate keys on that same argument, so it cannot authorize
 * a different request than the one that runs — the defect {@code AiGuardrailsWorkspaceSettingsGraphQlController}'s
 * javadoc records having shipped once.
 * </p>
 *
 * <p>
 * <b>Writes accept {@code ROLE_ADMIN} or the workspace-scoped {@code AI_GATEWAY_EDIT} permission</b>, so a workspace
 * admin can manage their own workspace's detection rules without tenant {@code ROLE_ADMIN}. {@code AI_GATEWAY_EDIT}
 * maps to {@code WorkspaceRole.ADMIN} via {@code AiGatewayPermissionScopeProvider}; {@code AI_GATEWAY_VIEW}, the only
 * other AI-gateway scope, stays on {@code WorkspaceRole.VIEWER} and is used only by the read below. Every write's gate
 * still opens with {@code hasAuthority('ROLE_ADMIN') or}, so a tenant admin keeps working everywhere they work today --
 * this widens access, it never narrows it.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnCoordinator
class AiGuardrailCustomRuleGraphQlController {

    private final AiGuardrailCustomRuleService aiGuardrailCustomRuleService;

    @SuppressFBWarnings("EI")
    AiGuardrailCustomRuleGraphQlController(AiGuardrailCustomRuleService aiGuardrailCustomRuleService) {
        this.aiGuardrailCustomRuleService = aiGuardrailCustomRuleService;
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_VIEW')")
    public List<AiGuardrailCustomRule> aiGuardrailCustomRules(@Argument long workspaceId) {
        return aiGuardrailCustomRuleService.getRules(workspaceId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or "
        + "hasPermission(#input.workspaceId, 'Workspace', 'AI_GATEWAY_EDIT')")
    public AiGuardrailCustomRule createAiGuardrailCustomRule(@Argument AiGuardrailCustomRuleInput input) {
        // Read once into a local: calling the accessor twice around a null check is what static analysis reads as a
        // possible null dereference, and it is right that the second call is not provably the same value.
        Double contextScore = input.contextScore();

        return aiGuardrailCustomRuleService.create(
            new AiGuardrailCustomRule(
                input.workspaceId(), input.type(), input.pattern(), input.kind(),
                BigDecimal.valueOf(input.score()), input.contextKeywords(), input.contextWindow(),
                contextScore == null ? null : BigDecimal.valueOf(contextScore)));
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_EDIT')")
    public AiGuardrailCustomRule updateAiGuardrailCustomRulePattern(
        @Argument long workspaceId, @Argument long id, @Argument String pattern) {

        return aiGuardrailCustomRuleService.updatePattern(id, workspaceId, pattern);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_EDIT')")
    public AiGuardrailCustomRule setAiGuardrailCustomRuleEnabled(
        @Argument long workspaceId, @Argument long id, @Argument boolean enabled) {

        return aiGuardrailCustomRuleService.setEnabled(id, workspaceId, enabled);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_EDIT')")
    public boolean deleteAiGuardrailCustomRule(@Argument long workspaceId, @Argument long id) {
        aiGuardrailCustomRuleService.delete(id, workspaceId);

        return true;
    }

    record AiGuardrailCustomRuleInput(
        long workspaceId, String type, String pattern, int kind, double score,
        @Nullable String contextKeywords, @Nullable Integer contextWindow, @Nullable Double contextScore) {
    }
}
