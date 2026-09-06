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
 * <b>Writes are {@code ROLE_ADMIN}, matching how guardrail settings are written.</b> A workspace-scoped edit scope
 * would be the better shape -- seeing a workspace's guardrail configuration is not the same as adding detection to it
 * -- but {@code AI_GATEWAY_VIEW} is the ONLY AI-gateway scope that exists, and it maps to {@code WorkspaceRole.VIEWER}.
 * Inventing an {@code AI_GATEWAY_EDIT} token would not have created a scope: an unregistered token makes
 * {@code hasPermission} deny, so every write would have failed for everyone including admins. Adding the scope properly
 * means the enum, {@code AiGatewayPermissionScopeProvider}'s role mapping and the EE gating test, which is its own
 * change.
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
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
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
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public AiGuardrailCustomRule updateAiGuardrailCustomRulePattern(
        @Argument long workspaceId, @Argument long id, @Argument String pattern) {

        return aiGuardrailCustomRuleService.updatePattern(id, workspaceId, pattern);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public AiGuardrailCustomRule setAiGuardrailCustomRuleEnabled(
        @Argument long workspaceId, @Argument long id, @Argument boolean enabled) {

        return aiGuardrailCustomRuleService.setEnabled(id, workspaceId, enabled);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public boolean deleteAiGuardrailCustomRule(@Argument long workspaceId, @Argument long id) {
        aiGuardrailCustomRuleService.delete(id, workspaceId);

        return true;
    }

    record AiGuardrailCustomRuleInput(
        long workspaceId, String type, String pattern, int kind, double score,
        @Nullable String contextKeywords, @Nullable Integer contextWindow, @Nullable Double contextScore) {
    }
}
