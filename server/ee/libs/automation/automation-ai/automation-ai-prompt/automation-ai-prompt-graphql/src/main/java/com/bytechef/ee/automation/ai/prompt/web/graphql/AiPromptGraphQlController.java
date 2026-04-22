/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.prompt.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.automation.ai.prompt.service.WorkspaceAiPromptService;
import com.bytechef.ee.platform.ai.observability.dto.AiPromptVersionMetrics;
import com.bytechef.ee.platform.ai.observability.repository.AiObservabilitySpanRepository;
import com.bytechef.ee.platform.ai.prompt.AiPrompt;
import com.bytechef.ee.platform.ai.prompt.AiPromptService;
import com.bytechef.ee.platform.ai.prompt.AiPromptVersion;
import com.bytechef.ee.platform.ai.prompt.AiPromptVersionService;
import com.bytechef.ee.platform.ai.prompt.AiPromptVersionType;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.constant.AuthorityConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

/**
 * GraphQL surface for prompts and prompt versions. Combines:
 * <ul>
 * <li>workspace-agnostic CRUD via {@link AiPromptService} (read/update/delete by id),
 * <li>workspace-scoped queries and creation via {@link WorkspaceAiPromptService}, and
 * <li>the gateway-only {@code AiPromptVersion.metrics} resolver, which aggregates LLM observability spans for
 * per-version invocation/cost/latency stats. The metrics resolver was historically a separate controller in
 * automation-ai-gateway-graphql; merging it here keeps the prompt schema in one place.
 * </ul>
 *
 * @author Ivica Cardic
 * @version ee
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
@ConditionalOnCoordinator
class AiPromptGraphQlController {

    private final AiObservabilitySpanRepository aiObservabilitySpanRepository;
    private final AiPromptService aiPromptService;
    private final AiPromptVersionService aiPromptVersionService;
    private final WorkspaceAiPromptService workspaceAiPromptService;

    @SuppressFBWarnings("EI")
    AiPromptGraphQlController(
        AiObservabilitySpanRepository aiObservabilitySpanRepository,
        AiPromptService aiPromptService,
        AiPromptVersionService aiPromptVersionService,
        WorkspaceAiPromptService workspaceAiPromptService) {

        this.aiObservabilitySpanRepository = aiObservabilitySpanRepository;
        this.aiPromptService = aiPromptService;
        this.aiPromptVersionService = aiPromptVersionService;
        this.workspaceAiPromptService = workspaceAiPromptService;
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public AiPrompt aiPrompt(@Argument long id) {
        return aiPromptService.getPrompt(id);
    }

    @QueryMapping
    @PreAuthorize("hasPermission(#workspaceId, 'WorkspaceRole', 'VIEWER')")
    public List<AiPrompt> aiPrompts(@Argument Long workspaceId) {
        return workspaceAiPromptService.getPromptsByWorkspace(workspaceId);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public List<AiPromptVersion> aiPromptVersions(@Argument Long promptId) {
        return aiPromptVersionService.getVersionsByPrompt(promptId);
    }

    @SchemaMapping(typeName = "AiPrompt", field = "versions")
    public List<AiPromptVersion> versions(AiPrompt prompt) {
        return aiPromptVersionService.getVersionsByPrompt(prompt.getId());
    }

    @SchemaMapping(typeName = "AiPromptVersion", field = "metrics")
    public AiPromptVersionMetrics metrics(AiPromptVersion version) {
        return aiObservabilitySpanRepository.aggregateMetricsByPromptVersion(version.getId())
            .orElseGet(AiPromptVersionMetrics::empty);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public AiPrompt createAiPrompt(@Argument CreateAiPromptInput input) {
        AiPrompt prompt = new AiPrompt(input.name());

        if (input.description() != null) {
            prompt.setDescription(input.description());
        }

        if (input.projectId() != null) {
            prompt.setProjectId(Long.parseLong(input.projectId()));
        }

        return workspaceAiPromptService.createInWorkspace(prompt, Long.parseLong(input.workspaceId()));
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public AiPromptVersion createAiPromptVersion(@Argument CreateAiPromptVersionInput input) {
        Long promptId = Long.parseLong(input.promptId());

        int nextVersionNumber = aiPromptVersionService.getNextVersionNumber(promptId);

        Authentication authentication = SecurityContextHolder.getContext()
            .getAuthentication();
        String createdBy = authentication != null ? authentication.getName() : "system";

        AiPromptVersionType versionType = AiPromptVersionType.valueOf(input.type());

        AiPromptVersion promptVersion = new AiPromptVersion(
            promptId, nextVersionNumber, versionType, input.content(), createdBy);

        if (input.commitMessage() != null) {
            promptVersion.setCommitMessage(input.commitMessage());
        }

        if (input.environment() != null) {
            promptVersion.setEnvironment(input.environment());
        }

        if (input.variables() != null) {
            promptVersion.setVariables(input.variables());
        }

        if (input.active() != null && input.active()) {
            promptVersion.setActive(true);
        }

        return aiPromptVersionService.create(promptVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public boolean deleteAiPrompt(@Argument long id) {
        workspaceAiPromptService.deleteInWorkspace(id);

        return true;
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public boolean setActiveAiPromptVersion(@Argument long promptVersionId, @Argument String environment) {
        aiPromptVersionService.setActiveVersion(promptVersionId, environment);

        return true;
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public AiPrompt updateAiPrompt(@Argument long id, @Argument UpdateAiPromptInput input) {
        AiPrompt prompt = aiPromptService.getPrompt(id);

        if (input.description() != null) {
            prompt.setDescription(input.description());
        }

        if (input.name() != null) {
            prompt.setName(input.name());
        }

        return aiPromptService.update(prompt);
    }

    @SuppressFBWarnings("EI")
    public record CreateAiPromptInput(String description, String name, String projectId, String workspaceId) {
    }

    @SuppressFBWarnings("EI")
    public record CreateAiPromptVersionInput(
        Boolean active, String commitMessage, String content, String environment, String promptId,
        String type, String variables) {
    }

    @SuppressFBWarnings("EI")
    public record UpdateAiPromptInput(String description, String name) {
    }
}
