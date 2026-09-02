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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
     * A drain that cannot finish must not outlast this, whatever the child left holding the pipe. The bound is the
     * execution's own timeout plus the runner's grace, so the assertion is a ceiling rather than a stopwatch.
     */
    private static final Duration DRAIN_CEILING = Duration.ofSeconds(20);

    private static final Pattern WORKING_DIRECTORY_PATTERN = Pattern.compile("\\S*bytechef-run-\\S+");

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

    /**
     * A child that exits 0 while a process it backgrounded still holds the stdout pipe's write end. Nothing above the
     * joins destroys anything - {@code waitFor} returned true and the process is not alive - so the drain threads are
     * all that stands between the task thread and that descendant's lifetime.
     *
     * <p>
     * Whether the read actually blocks there is a platform detail, not a contract: the JDK's process reaper drains and
     * closes its own copy of the read end when the child exits, and whether that wakes a read already parked on the
     * pipe differs between platforms. This test therefore asserts the ceiling and the captured output, and
     * {@link #testTheDrainWaitIsBoundedWhenAStreamNeverReachesEndOfFile()} pins the bound itself on every host.
     */
    @Test
    void testAnExitedChildWhoseDescendantHoldsTheStreamsDoesNotHangForever() throws InterruptedException {
        assumeTrue(isExecutable("/bin/sh"));

        String marker = uniqueSleepSeconds();

        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("sleep " + marker + " &", "echo still-captured"), Map.of(), Map.of(), Map.of(),
            List.of(), ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), Duration.ofSeconds(2),
            Map.of(), mock(ActionContext.class));

        try {
            long startNanos = System.nanoTime();

            TaskRunnerResult result = processTaskRunner.run(request);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

            assertThat(elapsed).isLessThan(DRAIN_CEILING);
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).contains("still-captured");
        } finally {
            destroyMarkerProcesses(marker);

            awaitNoMarkerProcesses(marker);
        }
    }

    /**
     * The bound B2 asked for, asserted where it can be asserted anywhere: a drain thread that never reaches the end of
     * its stream must not hold the task thread past the budget. An unbounded {@code join} never returns here.
     */
    @Test
    void testTheDrainWaitIsBoundedWhenAStreamNeverReachesEndOfFile() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch drainedStarted = new CountDownLatch(1);

        Thread neverDrained = Thread.ofVirtual()
            .start(() -> awaitQuietly(release));

        // stands in for the stream that did reach its end: it finishes on its own, so the failure below can only come
        // from the one that did not
        Thread drained = Thread.ofVirtual()
            .start(drainedStarted::countDown);

        try {
            long startNanos = System.nanoTime();

            boolean bothDrained = ProcessTaskRunner.joinDrainThreads(
                neverDrained, drained, Duration.ofMillis(200));

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

            assertThat(bothDrained).isFalse();
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            assertThat(drainedStarted.getCount()).isZero();
        } finally {
            release.countDown();
        }
    }

    /**
     * The budget shrinks with the time already spent waiting for the child, but never below the grace. Without that
     * floor a timed-out execution - which by definition has spent all of it - would abandon its drains instantly and
     * report a timeout with no stream tails at all, which is most of what makes the message useful.
     */
    @Test
    void testTheDrainBudgetIsWhatIsLeftOfTheTimeoutButNeverLessThanTheGrace() {
        Duration full = ProcessTaskRunner.drainBudget(Duration.ofSeconds(30), System.nanoTime());

        assertThat(full).isBetween(Duration.ofSeconds(25), Duration.ofSeconds(30));

        Duration exhausted = ProcessTaskRunner.drainBudget(
            Duration.ofSeconds(2), System.nanoTime() - Duration.ofSeconds(10)
                .toNanos());

        assertThat(exhausted).isPositive();
        assertThat(exhausted).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void testTheWorkingDirectoryIsRemovedAfterASuccessfulRun() throws IOException {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerResult result = processTaskRunner.run(commandsRequest(List.of("echo \"$BYTECHEF_WORKING_DIR\"")));

        assertWorkingDirectoryRemoved(result.stdout());
    }

    @Test
    void testTheWorkingDirectoryIsRemovedAfterANonZeroExit() throws IOException {
        assumeTrue(isExecutable("/bin/sh"));

        assertWorkingDirectoryRemoved(
            failureMessage(() -> processTaskRunner.run(
                commandsRequest(List.of("echo \"$BYTECHEF_WORKING_DIR\"", "exit 3")))));
    }

    @Test
    void testTheWorkingDirectoryIsRemovedAfterATimeout() throws IOException {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("echo \"$BYTECHEF_WORKING_DIR\"", "sleep 30"), Map.of(), Map.of(), Map.of(),
            List.of(), ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), Duration.ofSeconds(2),
            Map.of(), mock(ActionContext.class));

        assertWorkingDirectoryRemoved(failureMessage(() -> processTaskRunner.run(request)));
    }

    /**
     * The bootstrap's whole purpose: a {@code perform} return value has to come back as the execution's output. The
     * generated text is asserted on elsewhere, which says nothing about whether an interpreter can run it.
     */
    @Test
    void testTheJavaScriptBootstrapCarriesThePerformReturnValueToTheOutput() {
        assumeTrue(isOnPath("node"));

        TaskRunnerResult result = processTaskRunner.run(
            scriptRequest(
                "javascript", "function perform(input, context) { return {doubled: input.value * 2}; }",
                Map.of("value", 21)));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(Map.of("doubled", 42));
    }

    /**
     * The same script under the Truffle id {@code script}'s JavaScript action actually passes. Selecting the process
     * runner on that action failed 100% of the time while this identical run under {@code javascript} succeeded.
     */
    @Test
    void testTheTruffleJavaScriptIdReachesTheSameBootstrap() {
        assumeTrue(isOnPath("node"));

        TaskRunnerResult result = processTaskRunner.run(
            scriptRequest(
                "js", "function perform(input, context) { return {doubled: input.value * 2}; }",
                Map.of("value", 21)));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(Map.of("doubled", 42));
    }

    @Test
    void testTheJavaScriptBootstrapRaisesAFriendlyErrorForTheComponentBridge() {
        assumeTrue(isOnPath("node"));

        assertThatThrownBy(
            () -> processTaskRunner.run(
                scriptRequest(
                    "javascript", "function perform(input, context) { return context.component.example(); }",
                    Map.of())))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("context.component is not available under the process runner");
    }

    @Test
    void testThePythonBootstrapCarriesThePerformReturnValueToTheOutput() {
        assumeTrue(isOnPath("python3"));

        TaskRunnerResult result = processTaskRunner.run(
            scriptRequest(
                "python", "def perform(input, context):\n    return {\"doubled\": input[\"value\"] * 2}\n",
                Map.of("value", 21)));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(Map.of("doubled", 42));
    }

    @Test
    void testThePythonBootstrapRaisesAFriendlyErrorForTheComponentBridge() {
        assumeTrue(isOnPath("python3"));

        assertThatThrownBy(
            () -> processTaskRunner.run(
                scriptRequest(
                    "python", "def perform(input, context):\n    return context.component.example()\n", Map.of())))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("context.component is not available under the process runner");
    }

    @Test
    void testAClearedEnvironmentPointsHomeAtTheWorkingDirectory() {
        assumeTrue(isExecutable("/bin/sh"));

        TaskRunnerResult result = processTaskRunner.run(commandsRequest(List.of("echo \"[$HOME]\"")));

        assertThat(result.stdout()).contains("bytechef-run-");
    }

    /**
     * The one case where inheriting {@code HOME} is the point. Overriding it unconditionally left an operator who had
     * deliberately enabled inheritance without the server account's own home - the interpreter caches and credential
     * files under it are usually the reason the switch was flipped at all.
     */
    @Test
    void testAnInheritedEnvironmentKeepsTheServerAccountsHome() {
        assumeTrue(isExecutable("/bin/sh"));

        String hostHome = System.getenv("HOME");

        assumeTrue(hostHome != null && !hostHome.isBlank());

        ProcessTaskRunner enabledTaskRunner = new ProcessTaskRunner(
            applicationProperties(Map.of("inherit-environment-enabled", "true")));

        TaskRunnerRequest request = new TaskRunnerRequest(
            "shell", null, List.of("echo \"[$HOME]\""), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of("inheritEnvironment", true)),
            Duration.ofSeconds(30), Map.of(), mock(ActionContext.class));

        assertThat(enabledTaskRunner.run(request)
            .stdout()).contains("[" + hostHome + "]");
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

    /**
     * An assumption, not an assertion. {@link ProcessHandle.Info#commandLine()} is empty wherever the platform will not
     * disclose it - a restricted {@code /proc}, {@code hidepid}, a container without {@code SYS_PTRACE} - and there the
     * count is zero for every process alive, marker or not. That says nothing about the runner, so a test that cannot
     * observe its own precondition skips rather than fails.
     */
    private static void awaitMarkerProcesses(String marker) throws InterruptedException {
        await(() -> countMarkerProcesses(marker) > 0);

        assumeTrue(countMarkerProcesses(marker) > 0, "process command lines are not observable on this host");
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
        return markerProcessHandles(marker).count();
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

    /**
     * Asserts that the working directory named in the execution's own output no longer exists.
     *
     * <p>
     * The path is read back out of the run rather than guessed, so the assertion is about the directory this execution
     * created and not about whatever else the temp directory happens to hold while other tests run.
     */
    private static void assertWorkingDirectoryRemoved(String text) throws IOException {
        Matcher matcher = WORKING_DIRECTORY_PATTERN.matcher(text);

        assertThat(matcher.find()).isTrue();

        Path workingDirectoryPath = Path.of(matcher.group());

        assertThat(workingDirectoryPath).doesNotExist();
        assertThat(TaskRunnerWorkingDirectoryTest.bytechefRunDirectories()).doesNotContain(workingDirectoryPath);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
        }
    }

    private static void destroyMarkerProcesses(String marker) {
        markerProcessHandles(marker).forEach(ProcessHandle::destroyForcibly);
    }

    private static String failureMessage(Runnable runnable) {
        Throwable throwable = catchThrowable(runnable::run);

        assertThat(throwable).isNotNull();

        return String.valueOf(throwable.getMessage());
    }

    /**
     * Whether the command is resolvable through the same {@code PATH} the child process is handed, which is the only
     * {@code PATH} that decides whether the interpreter these tests need can be started at all.
     */
    private static boolean isOnPath(String command) {
        String path = System.getenv("PATH");

        if (path == null) {
            return false;
        }

        return Stream.of(path.split(File.pathSeparator))
            .filter(entry -> !entry.isBlank())
            .map(entry -> Path.of(entry)
                .resolve(command))
            .anyMatch(Files::isExecutable);
    }

    private static Stream<ProcessHandle> markerProcessHandles(String marker) {
        return ProcessHandle.allProcesses()
            .filter(processHandle -> {
                ProcessHandle.Info info = processHandle.info();

                Optional<String> commandLine = info.commandLine();

                return commandLine.map(line -> line.contains(marker))
                    .orElse(false);
            });
    }

    private static TaskRunnerRequest scriptRequest(String languageId, String script, Map<String, ?> input) {
        return new TaskRunnerRequest(
            languageId, script, List.of(), input, Map.of(), Map.of(), List.of(), ParametersFactory.create(Map.of()),
            ParametersFactory.create(Map.of()), Duration.ofSeconds(30), Map.of(), mock(ActionContext.class));
    }
}
