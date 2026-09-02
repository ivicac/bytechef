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

import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import java.util.List;
import java.util.Set;

/**
 * An environment a script or a set of commands can be executed in.
 *
 * <p>
 * Implementations register as Spring beans and are resolved by {@link #getType()} through {@link TaskRunnerRegistry}.
 * Adding a runner is one module contributing one bean plus one configuration key; no component changes.
 *
 * @author Ivica Cardic
 */
public interface TaskRunner {

    /**
     * The stable identifier this runner is selected by, in workflow JSON, configuration keys and
     * {@code displayCondition} expressions. Lower case, no spaces.
     */
    String getType();

    /**
     * The human-readable name shown in the runner select.
     */
    String getTitle();

    /**
     * This runner's own configuration properties, contributed into the {@code taskRunner} object property.
     *
     * <p>
     * <strong>Must return freshly constructed properties on every call.</strong> The caller stamps a
     * {@code displayCondition} onto each, and the DSL's property builders are mutable - a shared list would end up
     * carrying whichever condition was applied last, across every action that included it.
     */
    List<? extends ModifiableValueProperty<?, ?>> getProperties();

    /**
     * What this runner supports. A component offers this runner only if the capabilities it needs are present, and
     * hides properties whose capability is absent.
     */
    Set<TaskRunnerCapability> getCapabilities();

    /**
     * Rejects a request carrying configuration this runner cannot honour, before any work starts.
     *
     * @throws IllegalArgumentException if the request cannot be executed as specified
     */
    void validate(TaskRunnerRequest request);

    /**
     * Executes the request.
     */
    TaskRunnerResult run(TaskRunnerRequest request);
}
