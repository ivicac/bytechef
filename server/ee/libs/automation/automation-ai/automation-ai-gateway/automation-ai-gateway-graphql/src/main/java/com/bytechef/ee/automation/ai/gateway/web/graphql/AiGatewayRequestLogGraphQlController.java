/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.platform.ai.llm.usage.AiLlmUsage;
import com.bytechef.ee.platform.ai.llm.usage.service.AiLlmUsageService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.constant.AuthorityConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL controller for querying AI LLM Gateway request logs.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
@ConditionalOnCoordinator
class AiGatewayRequestLogGraphQlController {

    private final AiLlmUsageService aiGatewayRequestLogService;

    @SuppressFBWarnings("EI")
    AiGatewayRequestLogGraphQlController(AiLlmUsageService aiGatewayRequestLogService) {
        this.aiGatewayRequestLogService = aiGatewayRequestLogService;
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public List<AiLlmUsage> aiGatewayRequestLogs(
        @Argument long startDate, @Argument long endDate) {

        Instant start = Instant.ofEpochMilli(startDate);
        Instant end = Instant.ofEpochMilli(endDate);

        return aiGatewayRequestLogService.getRequestLogs(start, end);
    }

    @QueryMapping
    @PreAuthorize("hasPermission(#workspaceId, 'WorkspaceRole', 'VIEWER')")
    public List<AiLlmUsage> workspaceAiGatewayRequestLogs(
        @Argument Long workspaceId, @Argument long startDate, @Argument long endDate) {

        Instant start = Instant.ofEpochMilli(startDate);
        Instant end = Instant.ofEpochMilli(endDate);

        return aiGatewayRequestLogService.getRequestLogsByWorkspace(workspaceId, start, end);
    }
}
