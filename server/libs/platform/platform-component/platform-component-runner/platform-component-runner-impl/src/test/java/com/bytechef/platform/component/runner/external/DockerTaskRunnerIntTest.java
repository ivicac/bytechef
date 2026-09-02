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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Context.ContextFunction;
import com.bytechef.component.definition.Context.File;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * What {@code DockerTaskRunnerTest} cannot reach: everything that is true only because a real daemon behaves the way
 * the runner assumes it does.
 *
 * <p>
 * The mocked-daemon tests own the decisions the runner makes on its own - the allowlist, the closed network-mode set,
 * the removal on every exit path - and they own them better than a daemon test could, because a daemon that never
 * answers is easy to mock and hard to arrange. What they cannot own is the boundary itself: that a tar built from a
 * host temporary directory extracts into {@code /tmp} under modes the container's user can write, that the three
 * {@code BYTECHEF_*} paths name something that exists inside the container, that the appended bootstrap runs under a
 * real interpreter and leaves {@code output.json} where the runner looks for it, and that the archive coming back out
 * has the shape {@code copyArchiveFromContainer} actually produces. Each of those was asserted against a mock built
 * from the same understanding as the code, so a shared misunderstanding would have passed twice.
 *
 * <p>
 * The whole class is skipped rather than failed where no daemon answers, so a checkout without Docker still runs
 * {@code check} green - which is also why nothing here is the only test of anything. An image the registry will not
 * hand over skips it for the same reason and in the same place: an anonymous pull that a rate limit refuses is an
 * environment that cannot exercise the runner, not a runner defect.
 *
 * <p>
 * <strong>The image is pulled in {@code @BeforeAll}, and the class carries its own {@link Timeout}.</strong> This suite
 * runs on a classpath carrying {@code test-support}, whose {@code junit-platform.properties} caps every testable method
 * at 30 seconds - and {@code check} depends on {@code testIntegration}, which CI runs on a machine that HAS a daemon,
 * so it runs on every pull request. With the pull left to {@code IF_NOT_PRESENT} inside whichever test happened to run
 * first, a cold runner paid a Docker Hub pull inside that method's 30 seconds and reported the result as a JUnit
 * timeout on a test whose name suggests the runner broke. Both halves are needed: pulling in {@code @BeforeAll} moves
 * the cost out of every test method, and the class-level timeout raises the ceiling for the lifecycle method that now
 * pays it - {@code @BeforeAll}'s own default budget is 60 seconds, which a cold pull can exceed on its own.
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class DockerTaskRunnerIntTest {

    /**
     * One image for the whole class, so a run pays for at most one pull. It carries {@code /bin/sh} as well as
     * {@code python3}, so the shell commands and the Python bootstrap are both exercised against it, and it is the
     * language default the Python action resolves to - which is what lets the defaulting test run rather than only
     * validate.
     */
    private static final String IMAGE = "python:3.12-slim";

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(5);

    @BeforeAll
    static void assumeADaemonAnswersAndHoldsTheImage() {
        assumeTrue(isDaemonReachable(), "no docker daemon answered, so the docker runner cannot be exercised");
        assumeTrue(
            pullImage(), "the docker registry did not hand over " + IMAGE + ", so the docker runner cannot be " +
                "exercised");
    }

    /**
     * The round trip the whole file-based contract rests on: a file written into the host working directory is readable
     * inside the container, and a file the container writes is collected back on the host.
     */
    @Test
    void testAnInputFileReachesTheContainerAndAnOutputFileComesBack() {
        Map<String, byte[]> storedFiles = new LinkedHashMap<>();

        TaskRunnerRequest request = commandsRequest(
            List.of(
                "cat \"$BYTECHEF_WORKING_DIR/greeting.txt\" > \"$BYTECHEF_OUTPUT_DIR/echoed.txt\"",
                "echo collected"),
            Map.of("greeting.txt", "hello from the host"), List.of("*.txt"), Map.of("image", IMAGE), storedFiles);

        TaskRunnerResult taskRunnerResult = taskRunner(IMAGE).run(request);

        assertThat(taskRunnerResult.exitCode()).isZero();
        assertThat(taskRunnerResult.stdout()).contains("collected");
        assertThat(taskRunnerResult.outputFiles()).containsOnlyKeys("echoed.txt");
        assertThat(storedFiles).containsOnlyKeys("echoed.txt");
        assertThat(new String(storedFiles.get("echoed.txt"), StandardCharsets.UTF_8)).isEqualTo("hello from the host");
    }

    @Test
    void testOnlyTheOutputFilesMatchingAGlobAreCollected() {
        Map<String, byte[]> storedFiles = new LinkedHashMap<>();

        TaskRunnerRequest request = commandsRequest(
            List.of(
                "echo 'a,b' > \"$BYTECHEF_OUTPUT_DIR/report.csv\"",
                "mkdir -p \"$BYTECHEF_OUTPUT_DIR/nested\"",
                "echo 'c,d' > \"$BYTECHEF_OUTPUT_DIR/nested/deep.csv\"",
                "echo ignore me > \"$BYTECHEF_OUTPUT_DIR/notes.txt\""),
            Map.of(), List.of("*.csv"), Map.of("image", IMAGE), storedFiles);

        TaskRunnerResult taskRunnerResult = taskRunner(IMAGE).run(request);

        assertThat(taskRunnerResult.outputFiles()).containsOnlyKeys("report.csv");
        assertThat(storedFiles).containsOnlyKeys("report.csv");
    }

    /**
     * The tail is the whole point of the message: a container's standard error is gone the moment the container is
     * removed, which happens before the exception reaches the workflow.
     */
    @Test
    void testANonZeroExitFailsTheTaskWithTheStderrTail() {
        TaskRunnerRequest request = commandsRequest(
            List.of("echo 'the interpreter did not like that' >&2", "exit 7"), Map.of(), List.of(),
            Map.of("image", IMAGE), new LinkedHashMap<>());

        DockerTaskRunner dockerTaskRunner = taskRunner(IMAGE);

        assertThatThrownBy(() -> dockerTaskRunner.run(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("code 7")
            .hasMessageContaining("the interpreter did not like that");
    }

    /**
     * Rejected before a daemon is reached at all - the point being that a real daemon would happily have pulled and run
     * it.
     */
    @Test
    void testAnImageOutsideTheAllowlistIsRejected() {
        TaskRunnerRequest request = commandsRequest(
            List.of("echo hello"), Map.of(), List.of(), Map.of("image", "alpine:3.17"), new LinkedHashMap<>());

        DockerTaskRunner dockerTaskRunner = taskRunner(IMAGE);

        assertThatThrownBy(() -> dockerTaskRunner.run(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alpine:3.17")
            .hasMessageContaining("bytechef.script.runners.docker.properties.allowed-images");
    }

    /**
     * The bootstrap end to end: a real interpreter reads {@code input.json}, calls {@code perform}, and writes
     * {@code output.json} where the runner looks for it.
     */
    @Test
    void testAPerformReturnValueComesBackAsTheResultsVars() {
        TaskRunnerRequest request = scriptRequest(
            """
                def perform(input, context):
                    return {"greeting": "hi", "doubled": input["value"] * 2}
                """,
            Map.of("value", 21), Map.of("image", IMAGE));

        TaskRunnerResult taskRunnerResult = taskRunner(IMAGE).run(request);

        assertThat(taskRunnerResult.exitCode()).isZero();
        assertThat(taskRunnerResult.output()).isEqualTo(Map.of("greeting", "hi", "doubled", 42));
    }

    /**
     * The image defaulting proved against a daemon rather than only against {@code validate}: a request naming no image
     * runs, in the image its language resolves to.
     */
    @Test
    void testARequestNamingNoImageRunsInTheLanguagesDefaultImage() {
        TaskRunnerRequest request = scriptRequest(
            """
                def perform(input, context):
                    return {"ran": True}
                """,
            Map.of(), Map.of());

        TaskRunnerResult taskRunnerResult = taskRunner(IMAGE).run(request);

        assertThat(taskRunnerResult.exitCode()).isZero();
        assertThat(taskRunnerResult.output()).isEqualTo(Map.of("ran", true));
    }

    /**
     * The working directory is a host temporary directory, created {@code 0700}, and the daemon extracts the archive as
     * root - so before the archive carried modes of its own, a container told to run as anybody else could not write
     * {@code output/} and every such execution failed at its last step, after the work had succeeded.
     */
    @Test
    void testAContainerRunningAsANonRootUserCanWriteItsOutput() {
        TaskRunnerRequest request = scriptRequest(
            """
                def perform(input, context):
                    return {"ran": True}
                """,
            Map.of(), Map.of("image", IMAGE, "user", "1000:1000"));

        TaskRunnerResult taskRunnerResult = taskRunner(IMAGE).run(request);

        assertThat(taskRunnerResult.exitCode()).isZero();
        assertThat(taskRunnerResult.output()).isEqualTo(Map.of("ran", true));
    }

    /**
     * The asymmetry NB-2 was about, proved against a real image rather than against the environment list the runner
     * builds. {@code python:3.12-slim} declares {@code HOME=/root}, and a container told to run as {@code 1000:1000} -
     * a uid in no {@code /etc/passwd} - would otherwise land on {@code /} or {@code /root} and fail every interpreter
     * that writes a cache relative to it, on a workflow the process runner runs.
     */
    @Test
    void testTheContainersHomeIsItsWorkingDirectoryEvenForANumericUser() {
        TaskRunnerRequest request = commandsRequest(
            List.of("echo \"$HOME\"", "touch \"$HOME/cache-probe\""), Map.of(), List.of(),
            Map.of("image", IMAGE, "user", "1000:1000"), new LinkedHashMap<>());

        TaskRunnerResult taskRunnerResult = taskRunner(IMAGE).run(request);

        assertThat(taskRunnerResult.exitCode()).isZero();
        assertThat(taskRunnerResult.stdout()).contains("/tmp");
    }

    private static ApplicationProperties applicationProperties(String allowedImages) {
        ApplicationProperties.Script.Runner runner = new ApplicationProperties.Script.Runner();

        runner.setEnabled(true);
        runner.setProperties(Map.of("allowed-images", allowedImages));

        ApplicationProperties applicationProperties = new ApplicationProperties();

        ApplicationProperties.Script script = applicationProperties.getScript();

        Map<String, ApplicationProperties.Script.Runner> runners = new HashMap<>();

        runners.put("docker", runner);

        script.setRunners(runners);

        return applicationProperties;
    }

    private static TaskRunnerRequest commandsRequest(
        List<String> commands, Map<String, ?> inputFiles, List<String> outputFilePatterns,
        Map<String, ?> runnerParameters, Map<String, byte[]> storedFiles) {

        return new TaskRunnerRequest(
            "shell", null, commands, Map.of(), Map.of(), inputFiles, outputFilePatterns,
            ParametersFactory.create(Map.of()), ParametersFactory.create(runnerParameters), TIMEOUT, Map.of(),
            storingActionContext(storedFiles));
    }

    /**
     * Whether a daemon answers at all. Built exactly the way the runner builds its own client, so an unset
     * {@code DOCKER_HOST} is resolved the same way here as there - a probe that looked somewhere else would skip the
     * class on a machine where the runner works, or run it on one where it does not.
     */
    private static boolean isDaemonReachable() {
        DefaultDockerClientConfig dockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .build();

        DockerHttpClient dockerHttpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(dockerClientConfig.getDockerHost())
            .sslConfig(dockerClientConfig.getSSLConfig())
            .responseTimeout(Duration.ofSeconds(10))
            .build();

        try (DockerClient dockerClient = DockerClientImpl.getInstance(dockerClientConfig, dockerHttpClient)) {
            dockerClient.pingCmd()
                .exec();

            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * Pulls the one image this class runs in, once, before any test method is given its 30-second budget.
     *
     * <p>
     * The client is built the way {@link #isDaemonReachable} builds its own, so this reaches the same daemon the runner
     * will. A pull that fails - a registry that is not there, an anonymous rate limit - skips the class rather than
     * failing it, because it says nothing about the runner.
     */
    private static boolean pullImage() {
        DefaultDockerClientConfig dockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .build();

        DockerHttpClient dockerHttpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(dockerClientConfig.getDockerHost())
            .sslConfig(dockerClientConfig.getSSLConfig())
            .responseTimeout(PULL_TIMEOUT)
            .build();

        try (DockerClient dockerClient = DockerClientImpl.getInstance(dockerClientConfig, dockerHttpClient);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback()) {

            dockerClient.pullImageCmd(IMAGE)
                .exec(pullImageResultCallback);

            return pullImageResultCallback.awaitCompletion(PULL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();

            return false;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static TaskRunnerRequest scriptRequest(
        String script, Map<String, ?> input, Map<String, ?> runnerParameters) {

        return new TaskRunnerRequest(
            "python", script, List.of(), input, Map.of(), Map.of(), List.of(), ParametersFactory.create(Map.of()),
            ParametersFactory.create(runnerParameters), TIMEOUT, Map.of(),
            storingActionContext(new LinkedHashMap<>()));
    }

    /**
     * Records what the execution's output files were stored under. A plain mock never invokes the lambda handed to
     * {@code Context.file(...)}, so the captor applies it to a mock {@code File} instead - the same arrangement
     * {@code TaskRunnerOutputsTest} uses.
     */
    @SuppressWarnings("unchecked")
    private static ActionContext storingActionContext(Map<String, byte[]> storedFiles) {
        ActionContext actionContext = mock(ActionContext.class);
        File file = mock(File.class);

        ArgumentCaptor<ContextFunction<File, ?>> fileFunctionArgumentCaptor =
            ArgumentCaptor.forClass(ContextFunction.class);

        try {
            when(actionContext.file(fileFunctionArgumentCaptor.capture()))
                .thenAnswer(invocation -> {
                    ContextFunction<File, ?> fileFunction = fileFunctionArgumentCaptor.getValue();

                    return fileFunction.apply(file);
                });
            when(file.storeContent(anyString(), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(0);
                    InputStream inputStream = invocation.getArgument(1);

                    storedFiles.put(fileName, inputStream.readAllBytes());

                    return mock(FileEntry.class);
                });
        } catch (IOException exception) {
            throw new IllegalStateException("Could not arrange the storing action context", exception);
        }

        return actionContext;
    }

    private static DockerTaskRunner taskRunner(String allowedImages) {
        return new DockerTaskRunner(applicationProperties(allowedImages));
    }
}
