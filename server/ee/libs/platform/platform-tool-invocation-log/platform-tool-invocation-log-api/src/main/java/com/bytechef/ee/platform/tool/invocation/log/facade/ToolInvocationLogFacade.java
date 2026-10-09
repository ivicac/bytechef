/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.tool.invocation.log.facade;

import com.bytechef.ee.platform.tool.invocation.log.ToolInvocationLog;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;

/**
 * The HTTP-facing read side of the tool invocation log; implementations own authorization. The log mixes automation
 * rows, which carry a workspace and an environment, with embedded rows, which carry an environment but no workspace, so
 * it is read two ways: workspace members read one workspace's rows in one environment, tenant admins read every row,
 * optionally of one environment.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ToolInvocationLogFacade {

    /**
     * Every row of the tenant, narrowed by the given filters; a non-null {@code environmentId} keeps only that
     * environment's rows. Tenant admins only.
     */
    Page<ToolInvocationLog> getToolInvocationLogs(
        @Nullable Long environmentId, @Nullable ToolExecutionSurface surface, @Nullable ToolExecutionOutcome outcome,
        @Nullable Long mcpServerId, @Nullable Long connectedUserId, @Nullable Long integrationInstanceId,
        @Nullable Long appId, @Nullable Instant startDate, @Nullable Instant endDate, int pageNumber);

    /**
     * The rows of one workspace in one environment, narrowed by the given filters; a filter naming another workspace's
     * resource matches nothing.
     */
    Page<ToolInvocationLog> getWorkspaceToolInvocationLogs(
        long workspaceId, long environmentId, @Nullable ToolExecutionSurface surface,
        @Nullable ToolExecutionOutcome outcome, @Nullable Long mcpServerId, @Nullable Long connectedUserId,
        @Nullable Long integrationInstanceId, @Nullable Long appId, @Nullable Instant startDate,
        @Nullable Instant endDate, int pageNumber);
}
