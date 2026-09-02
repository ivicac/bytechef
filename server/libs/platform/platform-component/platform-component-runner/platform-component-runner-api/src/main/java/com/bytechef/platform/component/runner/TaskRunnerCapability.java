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

/**
 * An optional feature a {@link TaskRunner} may support.
 *
 * <p>
 * Capabilities are what keep the runner set open: a component asks the registry which runners declare the capability it
 * needs, rather than naming runners it knows about. They also drive property visibility, so a property a runner cannot
 * honour is never shown for it.
 *
 * @author Ivica Cardic
 */
public enum TaskRunnerCapability {

    /** Runs inline source written in the action's language. */
    INLINE_SCRIPT,

    /** Runs a list of shell commands. */
    COMMANDS,

    /** Exposes the in-JVM bridge letting a script invoke other components through {@code context.component}. */
    COMPONENT_BRIDGE,

    /** Materialises caller-supplied files into the execution's working directory. */
    INPUT_FILES,

    /** Collects files produced by the execution and stores them as file entries. */
    OUTPUT_FILES,

    /** Passes caller-supplied environment variables to the execution. */
    ENVIRONMENT
}
