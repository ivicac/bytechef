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

package com.bytechef.platform.component.runner.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Security Note: DMI_HARDCODED_ABSOLUTE_FILENAME - {@code /bin/sh} is the standard-conforming location every POSIX
 * system provides, and it is used here only to gate a test with {@code assumeTrue} when it is absent, never passed to a
 * process this test starts.
 *
 * <p>
 * Security Note: ENV_USE_PROPERTY_INSTEAD_OF_ENV - {@code testHostEnvironmentIsNotInherited} reads the host's own
 * environment on purpose, not portable system properties: the assertion is about the child process's environment not
 * inheriting the host's, so it has to compare against what an inherited environment would actually have carried.
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
@SuppressFBWarnings({
    "DMI_HARDCODED_ABSOLUTE_FILENAME", "ENV_USE_PROPERTY_INSTEAD_OF_ENV"
})
class ProcessTaskRunnerTest {

    /**
     * Stays under the suite's own 30-second per-test ceiling, so a tree that outlives the runner fails on this class's
     * assertion - which names what survived - rather than on a bare harness timeout.
     */
    private static final Duration PROCESS_DEATH_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Names whose value in the child proves nothing about whether the environment was cleared: the two
     * {@code applyEnvironment} puts back after clearing, and the ones a shell sets for itself on startup whatever it
     * inherited. On a minimal host one of the latter can easily be the alphabetically first name left standing.
     */
    private static final Set<String> NON_DISCRIMINATING_NAMES = Set.of(
        "HOME", "IFS", "OPTIND", "PATH", "PPID", "PS1", "PS2", "PS4", "PWD", "OLDPWD", "SHLVL", "_");

    private final ProcessTaskRunner processTaskRunner = new ProcessTaskRunner(new ApplicationProperties());

    @Test
    void testGetTypeIsProcess() {
        assertThat(processTaskRunner.getType()).isEqualTo("process");
    }

    @Test
    void testShellCommandsRunAndCaptureStdout() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerResult result = processTaskRunner.run(commandsRequest(List.of("echo hello-from-shell")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello-from-shell");
    }

    @Test
    void testNonZeroExitFailsWithTheStreamTailsInTheMessage() {
        assumeTrue(isExecutable("/bin/sh"));

        assertThatThrownBy(
            () -> processTaskRunner.run(
                commandsRequest(List.of("echo out-before-failure", "echo boom 1>&2", "exit 3"))))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("code 3")
                    .hasMessageContaining("out-before-failure")
                    .hasMessageContaining("boom");
    }

    @Test
    void testLargeStdoutDoesNotDeadlock() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerResult result = processTaskRunner.run(
            commandsRequest(List
                .of("i=0; while [ $i -lt 4000 ]; do echo 0123456789012345678901234567890123456789; i=$((i+1)); done")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isNotEmpty();
    }

    /**
     * Asserts on a host variable the runner does <strong>not</strong> re-seed. Asserting on {@code HOME} cannot fail:
     * it is put back unconditionally after the clear, so the child's value differs from the host's whether or not the
     * environment was ever cleared.
     */
    @Test
    void testHostEnvironmentIsNotInherited() {
        assumeTrue(isExecutable("/bin/sh"));

        Optional<String> clearedName = findClearedHostVariableName();

        assumeTrue(clearedName.isPresent());

        String name = clearedName.get();
        String hostValue = System.getenv(name);

        TaskRunnerResult result = processTaskRunner.run(commandsRequest(List.of("echo \"[$" + name + "]\"")));

        assertThat(result.stdout()).contains("[]");
        assertThat(result.stdout()).doesNotContain(hostValue);
    }

    @Test
    void testDeclaredEnvironmentReachesTheProcess() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("echo \"[$GREETING]\""), Map.of(), Map.of("GREETING", "hi"), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), Duration.ofSeconds(30), Map.of(),
            mock(ActionContext.class));

        assertThat(processTaskRunner.run(request)
            .stdout()).contains("[hi]");
    }

    /**
     * A declared {@code env} entry named like one of the contract variables must lose to the contract. Winning, it
     * would point the bootstrap's output at a directory the runner never reads, and every execution's output would
     * silently come back empty.
     */
    @Test
    void testDeclaredEnvironmentCannotOverrideTheContractVariables() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("echo '{\"ok\": true}' > \"$BYTECHEF_OUTPUT_DIR/output.json\""), Map.of(),
            Map.of("BYTECHEF_OUTPUT_DIR", "/nonexistent-bytechef-output-dir"), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), Duration.ofSeconds(30), Map.of(),
            mock(ActionContext.class));

        TaskRunnerResult result = processTaskRunner.run(request);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(Map.of("ok", true));
    }

    /**
     * The child holds an open pipe on its standard input unless the runner closes it, and a script reading input then
     * blocks until the whole execution times out instead of seeing the end of input.
     */
    @Test
    void testStandardInputIsClosedSoAReadSeesEndOfInput() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("read answer", "echo \"[read-returned]\""), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), Duration.ofSeconds(10), Map.of(),
            mock(ActionContext.class));

        TaskRunnerResult result = processTaskRunner.run(request);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("[read-returned]");
    }

    @Test
    void testTimeoutFailsWithATimedOutMessage() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("sleep 30"), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), Duration.ofSeconds(2), Map.of(),
            mock(ActionContext.class));

        assertThatThrownBy(() -> processTaskRunner.run(request))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("timed out");
    }

    /**
     * The message alone proves nothing about the process: it is thrown whether or not anything was killed. A
     * backgrounded sleep survives destroying the interpreter on its own, so the assertion is that neither it nor its
     * parent is left running under a marker only this execution used.
     */
    @Test
    void testTimeoutDestroysTheProcessTree() throws InterruptedException {
        assumeTrue(isExecutable("/bin/sh"));

        String marker = uniqueSleepSeconds();

        TaskRunnerRequest request = sleepRequest(marker, Duration.ofSeconds(2));

        assertThatThrownBy(() -> processTaskRunner.run(request))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("timed out");

        awaitNoMarkerProcesses(marker);
    }

    /**
     * The task worker cancels a running task by interrupting its thread, on job cancellation and on its own timeout.
     * Left unhandled that path returns from the wait without destroying anything, and the child outlives the workflow
     * with its working directory already deleted.
     */
    @Test
    void testInterruptionDestroysTheProcessTree() throws InterruptedException {
        assumeTrue(isExecutable("/bin/sh"));

        String marker = uniqueSleepSeconds();

        TaskRunnerRequest request = sleepRequest(marker, Duration.ofMinutes(5));

        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        Thread runnerThread = new Thread(() -> {
            try {
                processTaskRunner.run(request);
            } catch (RuntimeException exception) {
                failure.set(exception);
            }
        });

        runnerThread.start();

        awaitMarkerProcesses(marker);

        runnerThread.interrupt();
        runnerThread.join(PROCESS_DEATH_TIMEOUT.toMillis());

        awaitNoMarkerProcesses(marker);

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testValidateRejectsAnEmptyInterpreterOverride() {
        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("echo hi"), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of("interpreter", "   ")),
            Duration.ofSeconds(5), Map.of(), mock(ActionContext.class));

        assertThatThrownBy(() -> processTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * An explicit interpreter override used to carry a language the bootstrap cannot write a source file for past
     * validate, so the request failed only after the working directory had been created.
     */
    @Test
    void testValidateRejectsALanguageTheBootstrapCannotExecute() {
        TaskRunnerRequest request = new TaskRunnerRequest(
            "ruby", "puts 1", List.of(), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of("interpreter", "ruby")),
            Duration.ofSeconds(5), Map.of(), mock(ActionContext.class));

        assertThatThrownBy(() -> processTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ruby");
    }

    @Test
    void testValidateRejectsInheritEnvironmentWhenTheOperatorHasNotEnabledIt() {
        assertThatThrownBy(() -> processTaskRunner.validate(inheritEnvironmentRequest()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bytechef.script.runners.process.properties.inherit-environment-enabled");
    }

    @Test
    void testValidateAcceptsInheritEnvironmentWhenTheOperatorEnabledIt() {
        ProcessTaskRunner enabledTaskRunner = new ProcessTaskRunner(
            applicationProperties(Map.of("inherit-environment-enabled", "true")));

        enabledTaskRunner.validate(inheritEnvironmentRequest());
    }

    @Test
    void testInheritEnvironmentIsHiddenWhenTheOperatorHasNotEnabledIt() {
        assertThat(processTaskRunner.getProperties())
            .noneMatch(property -> "inheritEnvironment".equals(property.getName()));
    }

    @Test
    void testInheritEnvironmentIsOfferedWhenTheOperatorEnabledIt() {
        ProcessTaskRunner enabledTaskRunner = new ProcessTaskRunner(
            applicationProperties(Map.of("inherit-environment-enabled", "true")));

        assertThat(enabledTaskRunner.getProperties())
            .anyMatch(property -> "inheritEnvironment".equals(property.getName()));
    }

    @Test
    void testCapabilitiesExcludeTheComponentBridge() {
        assertThat(processTaskRunner.getCapabilities())
            .doesNotContain(com.bytechef.platform.component.runner.TaskRunnerCapability.COMPONENT_BRIDGE);
    }

    @Test
    void testGetPropertiesReturnsFreshInstances() {
        assertThat(processTaskRunner.getProperties()
            .getFirst())
                .isNotSameAs(
                    processTaskRunner.getProperties()
                        .getFirst());
    }

    private static ApplicationProperties applicationProperties(Map<String, String> runnerProperties) {
        ApplicationProperties.Script.Runner runner = new ApplicationProperties.Script.Runner();

        runner.setEnabled(true);
        runner.setProperties(runnerProperties);

        ApplicationProperties applicationProperties = new ApplicationProperties();

        ApplicationProperties.Script script = applicationProperties.getScript();

        Map<String, ApplicationProperties.Script.Runner> runners = new HashMap<>();

        runners.put("process", runner);

        script.setRunners(runners);

        return applicationProperties;
    }

    private static void awaitMarkerProcesses(String marker) throws InterruptedException {
        await(() -> countMarkerProcesses(marker) > 0);

        assertThat(countMarkerProcesses(marker)).isPositive();
    }

    private static void awaitNoMarkerProcesses(String marker) throws InterruptedException {
        await(() -> countMarkerProcesses(marker) == 0);

        assertThat(countMarkerProcesses(marker)).isZero();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + PROCESS_DEATH_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(50);
        }
    }

    private static TaskRunnerRequest commandsRequest(List<String> commands) {
        return new TaskRunnerRequest(
            "shell", null, commands, Map.of(), Map.of(), Map.of(), List.of(), ParametersFactory.create(Map.of()),
            ParametersFactory.create(Map.of()), Duration.ofSeconds(30), Map.of(), mock(ActionContext.class));
    }

    private static long countMarkerProcesses(String marker) {
        return ProcessHandle.allProcesses()
            .filter(processHandle -> {
                ProcessHandle.Info info = processHandle.info();

                Optional<String> commandLine = info.commandLine();

                return commandLine.map(line -> line.contains(marker))
                    .orElse(false);
            })
            .count();
    }

    /**
     * Returns a host environment variable name the runner neither re-seeds nor sets itself, and whose value is a
     * legible shell identifier with a non-blank value - the only kind of name whose absence in the child proves the
     * environment was cleared.
     */
    private static Optional<String> findClearedHostVariableName() {
        Map<String, String> hostEnvironment = System.getenv();

        return hostEnvironment.entrySet()
            .stream()
            .filter(entry -> {
                String name = entry.getKey();

                return !NON_DISCRIMINATING_NAMES.contains(name) && !name.startsWith("BYTECHEF_") &&
                    isShellIdentifier(name);
            })
            .filter(entry -> {
                String value = entry.getValue();

                return value != null && !value.isBlank();
            })
            .map(Map.Entry::getKey)
            .sorted()
            .findFirst();
    }

    private static TaskRunnerRequest inheritEnvironmentRequest() {
        return new TaskRunnerRequest(
            "shell", null, List.of("echo hi"), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of("inheritEnvironment", true)),
            Duration.ofSeconds(5), Map.of(), mock(ActionContext.class));
    }

    private static boolean isExecutable(String path) {
        return Files.isExecutable(Path.of(path));
    }

    private static boolean isShellIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }

        char first = name.charAt(0);

        if (first != '_' && !Character.isLetter(first)) {
            return false;
        }

        return name.chars()
            .allMatch(character -> character == '_' || Character.isLetterOrDigit(character));
    }

    private static TaskRunnerRequest sleepRequest(String marker, Duration timeout) {
        return new TaskRunnerRequest(
            "shell", null, List.of("sleep " + marker + " &", "sleep " + marker), Map.of(), Map.of(), Map.of(),
            List.of(), ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), timeout, Map.of(),
            mock(ActionContext.class));
    }

    /**
     * A sleep duration long enough to outlive the test and distinct enough to be unique, so a surviving process can be
     * attributed to this execution and no other.
     */
    private static String uniqueSleepSeconds() {
        long uniqueSuffix = System.nanoTime() % 1_000_000L;

        return String.valueOf(9_000_000L + uniqueSuffix);
    }
}
