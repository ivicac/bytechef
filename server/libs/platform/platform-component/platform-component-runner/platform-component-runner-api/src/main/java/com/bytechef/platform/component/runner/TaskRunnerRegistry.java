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

package com.bytechef.platform.component.runner;

import java.util.List;
import java.util.Set;

/**
 * Resolves a runner type to its {@link TaskRunner}, enforcing the operator allowlist.
 *
 * @author Ivica Cardic
 */
public interface TaskRunnerRegistry {

    /**
     * Returns the runner registered under the given type.
     *
     * @throws TaskRunnerNotEnabledException if no such runner is registered, or the operator has not enabled it
     */
    TaskRunner getTaskRunner(String type);

    /**
     * Returns the enabled runners declaring every one of the given capabilities, ordered by type so the assembled
     * property list is stable between builds.
     */
    List<TaskRunner> getTaskRunners(Set<TaskRunnerCapability> requiredCapabilities);
}
