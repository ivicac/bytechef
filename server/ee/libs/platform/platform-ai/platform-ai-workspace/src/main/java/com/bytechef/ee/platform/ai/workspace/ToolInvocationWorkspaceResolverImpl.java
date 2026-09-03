/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.workspace;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.tool.execution.ToolInvocationWorkspaceResolver;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Scopes an AI agent's tool-invocation audit events to the workspace of the run they belong to, through the same
 * {@link JobPrincipalWorkspaceResolver} the guardrails, workspace system prompt and Component Rules use. Only
 * {@link PlatformType#AUTOMATION} runs resolve; an embedded run or any resolution failure yields {@code null}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
class ToolInvocationWorkspaceResolverImpl implements ToolInvocationWorkspaceResolver {

    private final JobPrincipalWorkspaceResolver jobPrincipalWorkspaceResolver;

    ToolInvocationWorkspaceResolverImpl(JobPrincipalWorkspaceResolver jobPrincipalWorkspaceResolver) {
        this.jobPrincipalWorkspaceResolver = jobPrincipalWorkspaceResolver;
    }

    @Override
    public @Nullable Long resolve(@Nullable PlatformType platformType, @Nullable Long jobPrincipalId) {
        return jobPrincipalWorkspaceResolver.resolve(platformType, jobPrincipalId);
    }
}
