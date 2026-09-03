/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.workspace;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.constant.PlatformType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Resolves the workspace of the run identified by a ({@link PlatformType}, job-principal id) pair, for callers that
 * need to scope a policy — guardrails, a workspace system prompt, a Component Rule — to the workspace that requested
 * it.
 *
 * <p>
 * Resolution only applies to {@link PlatformType#AUTOMATION} runs with a non-null {@code jobPrincipalId} (interpreted
 * as a {@link ProjectDeployment} id, the same principal id {@code WorkflowExecutionCostApplicationEventListener}
 * resolves from): {@code jobPrincipalId} → {@link ProjectDeploymentService#getProjectDeployment(long)} →
 * {@link ProjectDeployment#getProjectId()} → {@link ProjectService#getProject(long)} →
 * {@link Project#getWorkspaceId()}. Embedded runs, a {@code null} {@code jobPrincipalId}, or any resolution failure
 * (deleted deployment/project, transient lookup error) resolve to {@code workspaceId = null} — the tenant default —
 * rather than throwing: only the workspace SCOPE is fail-open here. Whether a caller's own configuration applies is
 * never decided by this class, so a lookup failure must never be mistaken for "nothing configured".
 * </p>
 *
 * <p>
 * {@link ProjectDeploymentService} and {@link ProjectService} are optional (injected via {@link ObjectProvider}) rather
 * than hard constructor dependencies: this module is on the classpath of EE apps (e.g. ai-gateway-app, ai-copilot-app)
 * that carry neither {@code automation-configuration-service} nor its remote-client stubs. When either provider has no
 * bean to return, resolution degrades the same way a resolution failure does — {@code workspaceId = null}, the tenant
 * default.
 * </p>
 *
 * <p>
 * Resolution can run on every model call in an agent loop, so the (platformType, jobPrincipalId) → workspaceId lookup
 * is memoized in a short-lived Caffeine cache.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class JobPrincipalWorkspaceResolver {

    private static final Logger log = LoggerFactory.getLogger(JobPrincipalWorkspaceResolver.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private record WorkspaceCacheKey(PlatformType platformType, long jobPrincipalId) {
    }

    private final ObjectProvider<ProjectDeploymentService> projectDeploymentServiceProvider;
    private final ObjectProvider<ProjectService> projectServiceProvider;
    private final Cache<WorkspaceCacheKey, Optional<Long>> workspaceIdCache = Caffeine.newBuilder()
        .expireAfterWrite(CACHE_TTL)
        .build();

    @SuppressFBWarnings("EI2")
    public JobPrincipalWorkspaceResolver(
        ObjectProvider<ProjectDeploymentService> projectDeploymentServiceProvider,
        ObjectProvider<ProjectService> projectServiceProvider) {

        this.projectDeploymentServiceProvider = projectDeploymentServiceProvider;
        this.projectServiceProvider = projectServiceProvider;
    }

    public @Nullable Long resolve(@Nullable PlatformType platformType, @Nullable Long jobPrincipalId) {
        if (platformType != PlatformType.AUTOMATION || jobPrincipalId == null) {
            return null;
        }

        WorkspaceCacheKey cacheKey = new WorkspaceCacheKey(platformType, jobPrincipalId);

        Optional<Long> cachedWorkspaceId = workspaceIdCache.get(
            cacheKey, key -> Optional.ofNullable(fetchWorkspaceId(key.jobPrincipalId())));

        return cachedWorkspaceId.orElse(null);
    }

    private @Nullable Long fetchWorkspaceId(long jobPrincipalId) {
        ProjectDeploymentService projectDeploymentService = projectDeploymentServiceProvider.getIfAvailable();
        ProjectService projectService = projectServiceProvider.getIfAvailable();

        if (projectDeploymentService == null || projectService == null) {
            log.debug(
                "ProjectDeploymentService/ProjectService not available; resolving tenant-default workspace for job principal {}",
                jobPrincipalId);

            return null;
        }

        try {
            ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(jobPrincipalId);
            Project project = projectService.getProject(projectDeployment.getProjectId());

            return project.getWorkspaceId();
        } catch (RuntimeException exception) {
            log.warn(
                "Failed to resolve workspace for job principal {}; resolving tenant-default workspace",
                jobPrincipalId, exception);

            return null;
        }
    }
}
