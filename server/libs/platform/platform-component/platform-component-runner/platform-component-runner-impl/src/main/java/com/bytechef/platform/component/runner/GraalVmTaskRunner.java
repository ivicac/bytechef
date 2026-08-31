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

import com.bytechef.component.definition.ComponentDsl.ModifiableOption;
import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.definition.JobContextAware;
import com.bytechef.platform.component.polyglot.ScriptSandboxMode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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

    private static final Duration DEFAULT_TRUSTED_TIMEOUT = Duration.ofMinutes(5);

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
        boolean trustedEnabled = TaskRunnerTrustedMode.isEnabled(applicationProperties, GRAALVM);

        List<ModifiableOption<String>> options = new ArrayList<>();

        options.add(option("Strict", STRICT));

        if (trustedEnabled) {
            options.add(option("Trusted", TRUSTED));
        }

        // The description must match what the options list actually offers - an operator without the flag should
        // not read about a Trusted mode the select does not let them pick.
        String description = trustedEnabled
            ? "Strict runs the script with no access to the host: no file system, no environment, no process " +
                "creation, and CPU and memory ceilings. Trusted lifts every restriction and runs the script inside " +
                "the server process with full reflection; use it only on a single-tenant deployment you control."
            : "Strict runs the script with no access to the host: no file system, no environment, no process " +
                "creation, and CPU and memory ceilings.";

        return List.of(
            string(MODE)
                .label("Mode")
                .description(description)
                .options(options)
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

        ScriptSandboxMode scriptSandboxMode = getMode(request);

        // Unlike the four rejections above, a stale timeout is not obviously wrong on its face - it is a valid
        // value for the other mode. Rejecting it here, loudly, is what stops a workflow that carried timeout: 5
        // under the process runner from silently keeping it after an author switches to graalvm, where it would
        // apply a wall clock strict was never meant to have.
        if (scriptSandboxMode == ScriptSandboxMode.STRICT && request.timeout() != null) {
            throw new IllegalArgumentException(
                "The GraalVM runner's strict mode does not support timeout; strict already runs under CPU and " +
                    "heap ceilings, and a wall clock would kill a script that is merely waiting on I/O, such as a " +
                    "slow call through the component bridge");
        }
    }

    @Override
    public TaskRunnerResult run(TaskRunnerRequest request) {
        validate(request);

        ScriptSandboxMode scriptSandboxMode = getMode(request);

        // The component bridge resolves connections and other components' actions through the job context, so this
        // runner requires the request's action context to be JobContextAware - a requirement the record documents,
        // since the type alone does not carry it.
        Object output = polyglotEngine.execute(
            scriptSandboxMode, getTimeout(request, scriptSandboxMode), request.languageId(),
            request.inputParameters(), request.componentConnections(), (JobContextAware) request.actionContext());

        return TaskRunnerResult.ofOutput(output);
    }

    /**
     * Resolves the wall-clock ceiling, which is a per-mode decision rather than a constant.
     *
     * <p>
     * A {@link ScriptSandboxMode#STRICT} execution already runs under the {@code CONSTRAINED} policy's CPU and heap
     * ceilings, so it needs no wall clock and gets none. Imposing one would newly kill scripts that were never bounded
     * before: {@code sandbox.MaxCPUTime} meters CPU, and a script blocked in a host call through the component bridge -
     * an HTTP request to a slow API, a long chain of component invocations - burns wall clock while accruing almost no
     * guest CPU.
     *
     * <p>
     * A {@link ScriptSandboxMode#TRUSTED} execution has no ceiling of any kind, because {@code sandbox.*} options exist
     * only under {@code CONSTRAINED}. There the watchdog is the only thing that can stop a runaway script, so the
     * default applies.
     *
     * <p>
     * {@code request.timeout()} reaches here non-null only for {@link ScriptSandboxMode#TRUSTED} - {@link #validate}
     * rejects a non-null timeout under {@code STRICT} before {@link #run} ever calls this method, so the first branch
     * below is unreachable for {@code STRICT} rather than merely untested.
     */
    private static @Nullable Duration getTimeout(TaskRunnerRequest request, ScriptSandboxMode scriptSandboxMode) {
        Duration timeout = request.timeout();

        if (timeout != null) {
            return timeout;
        }

        return scriptSandboxMode == ScriptSandboxMode.TRUSTED ? DEFAULT_TRUSTED_TIMEOUT : null;
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
