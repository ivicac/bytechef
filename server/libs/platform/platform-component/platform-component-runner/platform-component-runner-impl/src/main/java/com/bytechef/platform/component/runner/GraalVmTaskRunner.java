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

import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.MODE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.STRICT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TRUSTED;

import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.definition.JobContextAware;
import com.bytechef.platform.component.polyglot.ScriptSandboxMode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Runs the script in this JVM, in a GraalVM polyglot context.
 *
 * <p>
 * The only runner that can offer {@link TaskRunnerCapability#COMPONENT_BRIDGE}: the bridge letting a script invoke
 * other components is a live host object, so it cannot cross a process boundary.
 *
 * <p>
 * This class is also where the operator's trusted-mode escape hatch is enforced. The runner select in the editor hides
 * the Trusted option when the operator has not enabled it, but that is UX only - a hand-edited workflow reaches
 * {@link #validate} instead, and trusted mode grants guest code the whole JVM.
 *
 * @author Ivica Cardic
 */
@Component
@SuppressFBWarnings("EI")
public class GraalVmTaskRunner implements TaskRunner {

    private final PolyglotEngine polyglotEngine;
    private final ApplicationProperties applicationProperties;

    public GraalVmTaskRunner(PolyglotEngine polyglotEngine, ApplicationProperties applicationProperties) {
        this.polyglotEngine = polyglotEngine;
        this.applicationProperties = applicationProperties;
    }

    @Override
    public String getType() {
        return GRAALVM;
    }

    @Override
    public String getTitle() {
        return "GraalVM";
    }

    @Override
    public List<? extends ModifiableValueProperty<?, ?>> getProperties() {
        return List.of(
            string(MODE)
                .label("Mode")
                .description(
                    "Strict runs the script with no access to the host: no file system, no environment, no process " +
                        "creation, and CPU and memory ceilings. Trusted lifts every restriction and runs the script " +
                        "inside the server process with full reflection; use it only on a single-tenant deployment " +
                        "you control.")
                .options(option("Strict", STRICT), option("Trusted", TRUSTED))
                .defaultValue(STRICT)
                .required(false));
    }

    @Override
    public Set<TaskRunnerCapability> getCapabilities() {
        return Set.of(TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.COMPONENT_BRIDGE);
    }

    @Override
    public void validate(TaskRunnerRequest request) {
        if (!request.commands()
            .isEmpty()) {

            throw new IllegalArgumentException("The GraalVM runner does not support commands; use a script instead");
        }

        if (!request.inputFiles()
            .isEmpty()) {

            throw new IllegalArgumentException("The GraalVM runner does not support inputFiles");
        }

        if (!request.outputFilePatterns()
            .isEmpty()) {

            throw new IllegalArgumentException("The GraalVM runner does not support outputFiles");
        }

        if (!request.env()
            .isEmpty()) {

            throw new IllegalArgumentException("The GraalVM runner does not support env");
        }

        getMode(request);
    }

    @Override
    public TaskRunnerResult run(TaskRunnerRequest request) {
        validate(request);

        Object output = polyglotEngine.execute(
            getMode(request), request.timeout(), request.languageId(), request.inputParameters(),
            request.componentConnections(), (JobContextAware) request.actionContext());

        return TaskRunnerResult.ofOutput(output);
    }

    private ScriptSandboxMode getMode(TaskRunnerRequest request) {
        String mode = request.runnerParameters()
            .getString(MODE, STRICT);

        ScriptSandboxMode scriptSandboxMode = switch (mode) {
            case STRICT -> ScriptSandboxMode.STRICT;
            case TRUSTED -> ScriptSandboxMode.TRUSTED;
            default -> throw new IllegalArgumentException(
                "Unknown GraalVM sandbox mode '%s'; expected '%s' or '%s'".formatted(mode, STRICT, TRUSTED));
        };

        if (scriptSandboxMode == ScriptSandboxMode.TRUSTED &&
            !TaskRunnerTrustedMode.isEnabled(applicationProperties, GRAALVM)) {

            throw new IllegalArgumentException(
                "The GraalVM runner's '%s' mode is not enabled. An operator must enable it with %s=true."
                    .formatted(TRUSTED, TaskRunnerTrustedMode.getPropertyName(GRAALVM)));
        }

        return scriptSandboxMode;
    }
}
