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

package com.bytechef.platform.component.definition;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.constant.PlatformType;
import org.jspecify.annotations.Nullable;

/**
 * @author Ivica Cardic
 */
public interface JobContextAware {

    /**
     * Retrieves the unique identifier for the job the current execution belongs to, if available.
     *
     * @return the job ID as a {@link Long}, or {@code null} if the execution is not bound to a persisted job (e.g.
     *         editor-environment runs).
     */
    @Nullable
    Long getJobId();

    /**
     * Retrieves the unique identifier associated with the job principal: a project-deployment id under
     * {@link PlatformType#AUTOMATION}, an integration-instance id under {@link PlatformType#EMBEDDED}.
     *
     * <p>
     * Declared here rather than on {@link ActionContextAware} alone because it is half of the pair an owner is derived
     * from, and an action context and a cluster-element context must derive it the same way. Two declarations would be
     * two chances for the branches to drift.
     *
     * @return the job principal ID as a {@link Long}, or {@code null} if no job principal ID is set.
     */
    @Nullable
    Long getJobPrincipalId();

    /**
     * Retrieves the platform type the current execution runs under, which is the pool half of the owner pair.
     *
     * @return the {@link PlatformType} if available, {@code null} otherwise.
     */
    @Nullable
    PlatformType getPlatformType();

    /**
     * Converts the provided component information and connection details into an {@link ActionContext} instance.
     *
     * @param componentName       the name of the component associated with the action context.
     * @param componentVersion    the version of the component.
     * @param actionName          the name of the action being executed.
     * @param componentConnection an optional {@link ComponentConnection} containing connection details for the
     *                            component; can be null.
     * @return an instance of {@link ActionContext} representing the provided information and connection details.
     */
    ActionContext toActionContext(
        String componentName, int componentVersion, String actionName,
        @Nullable ComponentConnection componentConnection);

    /**
     * Retrieves the unique identifier for the environment.
     *
     * @return the environment ID as a {@link Long}, or {@code null} if the ID is not available.
     */
    @Nullable
    Long getEnvironmentId();
}
