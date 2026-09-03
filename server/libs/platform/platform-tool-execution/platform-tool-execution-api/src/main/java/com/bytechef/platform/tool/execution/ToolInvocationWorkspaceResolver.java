/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.tool.execution;

import com.bytechef.platform.constant.PlatformType;
import org.jspecify.annotations.Nullable;

/**
 * CE-side SPI seam that lets a tool-invocation writer running inside a workflow (an AI agent's rule block, approval
 * gate, or gate resolution) scope its {@link ToolExecutionEvent} to the workspace of the run, without depending on the
 * EE module that knows how to resolve it. When no implementation is registered — CE, or an app variant without the EE
 * resolver — writers leave {@link ToolExecutionEvent#workspaceId()} unset.
 *
 * @author Ivica Cardic
 */
public interface ToolInvocationWorkspaceResolver {

    /**
     * Returns the workspace the run identified by {@code platformType} and {@code jobPrincipalId} belongs to, or
     * {@code null} when there is none to resolve (a non-AUTOMATION run, no job principal, or a resolution failure).
     * Never throws.
     *
     * @param platformType   the platform the run belongs to, or {@code null} when the calling context carries none
     * @param jobPrincipalId the run's job principal id (a project deployment id), or {@code null} when unknown
     * @return the run's workspace id, or {@code null}
     */
    @Nullable
    Long resolve(@Nullable PlatformType platformType, @Nullable Long jobPrincipalId);
}
