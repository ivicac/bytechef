/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolation;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailViolationService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * Read surface for per-detection guardrail drill-down records.
 *
 * <p>
 * Authorization mirrors {@code AiGuardrailsWorkspaceSettingsGraphQlController}: a workspace read needs the
 * {@code AI_GATEWAY_VIEW} scope on that workspace, and the null-{@code workspaceId} tenant-default bucket is admin-only
 * because it has no workspace to scope against. The gate keys on the same argument the body reads, so it cannot
 * authorize a different request than the one that runs -- the defect that section's javadoc records having shipped
 * once.
 * </p>
 *
 * <p>
 * Query only. Records are written by the guardrail path and swept by retention; nothing may create, edit or delete one
 * through the API, because a record whose subject can rewrite it is not evidence of anything.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnCoordinator
class AiGuardrailViolationGraphQlController {

    private static final int DEFAULT_LIMIT = 100;

    private final AiGuardrailViolationService aiGuardrailViolationService;

    @SuppressFBWarnings("EI")
    AiGuardrailViolationGraphQlController(AiGuardrailViolationService aiGuardrailViolationService) {
        this.aiGuardrailViolationService = aiGuardrailViolationService;
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or (#workspaceId != null "
        + "&& hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_VIEW'))")
    public List<AiGuardrailViolation> aiGuardrailViolations(
        @Argument @Nullable Long workspaceId, @Argument @Nullable Integer limit) {

        return aiGuardrailViolationService.getViolations(workspaceId, limit == null ? DEFAULT_LIMIT : limit);
    }
}
