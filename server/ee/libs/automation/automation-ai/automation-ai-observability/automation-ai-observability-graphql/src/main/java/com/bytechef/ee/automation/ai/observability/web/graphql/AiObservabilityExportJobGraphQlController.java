/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.observability.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.automation.ai.observability.service.AiObservabilityExportExecutor;
import com.bytechef.ee.automation.ai.observability.service.WorkspaceAiObservabilityExportJobService;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityExportFormat;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityExportJob;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityExportJobType;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityExportScope;
import com.bytechef.ee.platform.ai.observability.service.AiObservabilityExportJobService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.util.SecurityUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * @version ee
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
@ConditionalOnCoordinator
class AiObservabilityExportJobGraphQlController {

    private final AiObservabilityExportExecutor aiObservabilityExportExecutor;
    private final AiObservabilityExportJobService aiObservabilityExportJobService;
    private final WorkspaceAiObservabilityExportJobService workspaceAiObservabilityExportJobService;

    @SuppressFBWarnings("EI")
    AiObservabilityExportJobGraphQlController(
        AiObservabilityExportExecutor aiObservabilityExportExecutor,
        AiObservabilityExportJobService aiObservabilityExportJobService,
        WorkspaceAiObservabilityExportJobService workspaceAiObservabilityExportJobService) {

        this.aiObservabilityExportExecutor = aiObservabilityExportExecutor;
        this.aiObservabilityExportJobService = aiObservabilityExportJobService;
        this.workspaceAiObservabilityExportJobService = workspaceAiObservabilityExportJobService;
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public AiObservabilityExportJob aiObservabilityExportJob(@Argument long id) {
        return aiObservabilityExportJobService.getExportJob(id);
    }

    @QueryMapping
    @PreAuthorize("hasPermission(#workspaceId, 'WorkspaceRole', 'VIEWER')")
    public List<AiObservabilityExportJob> aiObservabilityExportJobs(@Argument Long workspaceId) {
        return workspaceAiObservabilityExportJobService.getExportJobsByWorkspace(workspaceId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public AiObservabilityExportJob createAiObservabilityExportJob(
        @Argument Long workspaceId, @Argument Long projectId,
        @Argument AiObservabilityExportFormat format, @Argument AiObservabilityExportScope scope,
        @Argument String filters, @Argument AiObservabilityExportJobType type,
        @Argument String cronExpression) {

        String currentUserLogin = SecurityUtils.getCurrentUserLogin();

        AiObservabilityExportJobType effectiveType = type != null ? type : AiObservabilityExportJobType.ON_DEMAND;

        AiObservabilityExportJob exportJob = new AiObservabilityExportJob(
            effectiveType, format, scope, currentUserLogin);

        exportJob.setProjectId(projectId);
        exportJob.setFilters(filters);
        exportJob.setCronExpression(cronExpression);

        AiObservabilityExportJob savedExportJob =
            workspaceAiObservabilityExportJobService.createInWorkspace(exportJob, workspaceId);

        // ON_DEMAND: run once immediately. SCHEDULED: service layer registered the cron; first run will come from the
        // scheduler event listener.
        if (effectiveType == AiObservabilityExportJobType.ON_DEMAND) {
            aiObservabilityExportExecutor.executeExport(savedExportJob.getId());
        }

        return savedExportJob;
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public AiObservabilityExportJob cancelAiObservabilityExportJob(@Argument long id) {
        return aiObservabilityExportJobService.cancel(id);
    }
}
