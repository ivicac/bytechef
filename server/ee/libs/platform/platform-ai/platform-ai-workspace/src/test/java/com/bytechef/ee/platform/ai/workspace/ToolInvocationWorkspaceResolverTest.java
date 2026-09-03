/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.tool.execution.ToolInvocationWorkspaceResolver;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ToolInvocationWorkspaceResolverTest {

    private final JobPrincipalWorkspaceResolver jobPrincipalWorkspaceResolver = mock(
        JobPrincipalWorkspaceResolver.class);

    private final ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver =
        new ToolInvocationWorkspaceResolverImpl(jobPrincipalWorkspaceResolver);

    @Test
    void testResolvesTheWorkspaceOfTheRunsJobPrincipal() {
        when(jobPrincipalWorkspaceResolver.resolve(PlatformType.AUTOMATION, 3L)).thenReturn(42L);

        assertThat(toolInvocationWorkspaceResolver.resolve(PlatformType.AUTOMATION, 3L)).isEqualTo(42L);
    }

    @Test
    void testAnUnresolvableRunResolvesToNoWorkspace() {
        when(jobPrincipalWorkspaceResolver.resolve(PlatformType.EMBEDDED, 3L)).thenReturn(null);

        assertThat(toolInvocationWorkspaceResolver.resolve(PlatformType.EMBEDDED, 3L)).isNull();
    }
}
