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

import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.CPU;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.DOCKER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.ENTRYPOINT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.EXTRA_HOSTS;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.IMAGE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.MEMORY;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.NETWORK_MODE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.PULL_POLICY;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.USER;

import com.bytechef.component.definition.ComponentDsl.ModifiableOption;
import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.polyglot.GuestLoggingOutputStream;
import com.bytechef.platform.component.runner.TaskRunner;
import com.bytechef.platform.component.runner.TaskRunnerCapability;
import com.bytechef.platform.component.runner.TaskRunnerOperatorFlag;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.WaitResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs the task in a single-use container, on the local daemon or on whichever daemon {@code host} names.
 *
 * <p>
 * This is {@code ProcessTaskRunner}'s sibling and shares its whole shape: the same working directory, the same appended
 * bootstrap, the same bounded stream capture tee'd to the guest logger, the same five-key result. Only the way the
 * interpreter is reached differs - a container image instead of an executable on the host's {@code PATH}.
 *
 * <p>
 * <strong>The allowlist check in {@link #validate} is the security boundary.</strong> The image select's options are
 * assembled from the same allowlist, but that is UX only: a hand-edited workflow reaches {@code validate} carrying any
 * image string at all, and it must be rejected there. This is the two-layer arrangement the runner allowlist already
 * uses, and the empty allowlist is closed rather than open, so an operator who enables the runner without naming an
 * image gets nothing runnable rather than everything. A workflow that names no image at all is resolved against
 * {@code DEFAULT_IMAGES} and then checked like any other - the default is a convenience, never a way around the
 * allowlist.
 *
 * <p>
 * <strong>There is no {@code volumes} property.</strong> A host bind mount chosen by a workflow author is a direct
 * container escape - {@code /} mounted read-write is the whole host - so no property offers one and no bind is ever put
 * on the container. {@code DockerOperatorSettings.isVolumeMountAllowed()} exists and is deliberately read by nothing
 * here: the operator switch is in place for the version that adds the property, and reading it before that property
 * exists would only invite someone to wire a mount to it.
 *
 * <p>
 * Files cross the boundary as tar archives rather than as mounts, for the reason {@link ContainerArchive} records: a
 * bind mount is resolved by the daemon, so ByteChef running in its own container with the socket mounted would name a
 * host path the daemon cannot see and get an empty directory instead of an error. The archive coming back out is
 * written by guest code, so it is extracted through {@code ContainerArchive}'s guards and its byte ceiling.
 *
 * <p>
 * The working directory is copied to {@code /tmp} inside the container rather than to a path of its own. Docker's
 * archive endpoint extracts only into a directory that <strong>already exists</strong> in the container, and a
 * directory named at creation time is not created until the container starts - which is after the copy. {@code /tmp} is
 * the one writable directory every image carrying an interpreter provides. The three {@code BYTECHEF_*} names are
 * therefore rewritten from the host's paths onto the container's; they are read from
 * {@link TaskRunnerWorkingDirectory#getEnvironment()} rather than restated here, so the names still have exactly one
 * owner.
 *
 * <p>
 * The timeout bounds the two operations that can block without limit - the pull and the wait for the exit code - by
 * what is left of the deadline, and every synchronous step in between checks that something is left <em>before</em> it
 * starts. That check is not a bound on the call it precedes: {@code inspectImage}, {@code createContainer}, the two
 * archive copies and {@code removeContainer} are synchronous HTTP calls to the daemon, and a daemon that accepts the
 * connection and then stops answering holds one of them open however much of the deadline was left. The client is
 * therefore built with a response timeout of the execution's own timeout, so a stalled call fails rather than hanging
 * the task thread. The honest statement of the ceiling is: the deadline governs when a step may start, and a step
 * already in flight when the daemon goes quiet ends one response timeout later - so a pathological daemon can carry an
 * execution to roughly twice its timeout, not past it indefinitely.
 *
 * <p>
 * The container is force-removed in a {@code finally} that covers every exit from the moment it exists: success, a
 * non-zero exit, a timeout, a failed copy, a rejected archive, and the interruption {@code TaskWorker} uses to cancel a
 * running task. A leaked container is this runner's version of a leaked process tree, and it outlives not just the task
 * but the deployment.
 *
 * <p>
 * A non-root {@code user} is passed through to the container and can write its output, because the archive carries its
 * own modes rather than the host temporary directory's - see {@link ContainerArchive#toTar}. The host copy stays as
 * restrictive as it was created. {@code HOME} points at the container's working directory for the reason
 * {@code ProcessTaskRunner} points it at its own: several interpreters write caches relative to it, and a numeric
 * {@code user} that is in no image's {@code /etc/passwd} otherwise gets {@code /}, where {@code pip install --user}
 * fails on a permission error the same workflow never hits under the process runner.
 *
 * <p>
 * <strong>Every container is bounded whether or not its workflow asked to be.</strong> A PID ceiling, a CPU allowance
 * and a memory allowance go on every host configuration, from {@link DockerOperatorSettings}' defaults when the
 * workflow names none, and a workflow that names one above the operator's maximum is refused rather than quietly
 * clamped - the same answer the image allowlist gives an image it does not hold, and for the same reason: an execution
 * silently given a sixteenth of what it asked for fails later, somewhere else, as a timeout. The PID ceiling has no
 * workflow-facing property at all, because {@code :(){ :|:& };:} in a {@code shell} action exhausts the host's PID
 * space long before the execution timeout fires, and a limit an attacker opts into is not a limit.
 *
 * @author Ivica Cardic
 */
@Component
@SuppressFBWarnings("EI")
public class DockerTaskRunner implements TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerTaskRunner.class);

    private static final String CONTAINER_DIRECTORY = "/tmp";
    private static final String CONTAINER_OUTPUT_DIRECTORY = CONTAINER_DIRECTORY + "/output";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration LOG_DRAIN_GRACE = Duration.ofSeconds(5);

    private static final long NANO_CPUS_PER_CPU = 1_000_000_000L;

    private static final Pattern MEMORY_PATTERN = Pattern.compile("^(\\d+)\\s*([bkmg])?$", Pattern.CASE_INSENSITIVE);

    private static final Map<ExternalLanguage, List<String>> DEFAULT_ENTRYPOINTS = Map.of(
        ExternalLanguage.JAVASCRIPT, List.of("node"),
        ExternalLanguage.PYTHON, List.of("python3"),
        ExternalLanguage.SHELL, List.of("/bin/sh"));

    /**
     * The image each language runs in when a workflow names none.
     *
     * <p>
     * The design spec states these per action - {@code shell} to {@code ubuntu:24.04}, {@code python} to
     * {@code python:3.12-slim}, {@code node} to {@code node:22-alpine} - but they are held here, beside the default
     * entrypoints they pair with, rather than contributed by the actions. {@code image} is this runner's property;
     * {@code TaskRunner#getProperties()} takes no arguments and the factory that assembles the {@code taskRunner}
     * object never learns the action's language, so an action could only supply a default for it through a seam that
     * let a component reach into a runner's properties - which is the component-to-runner coupling the whole feature
     * has avoided. Keyed by language rather than by action, the table also covers {@code script}'s actions and any
     * later component that runs the same three languages, without either of them naming Docker.
     *
     * <p>
     * <strong>A default is a convenience, not a bypass.</strong> The image it resolves to goes through
     * {@link #validate}'s allowlist check exactly as a named one does, so an operator who has not allowed
     * {@code ubuntu:24.04} gets a rejection naming it rather than a run.
     */
    private static final Map<ExternalLanguage, String> DEFAULT_IMAGES = Map.of(
        ExternalLanguage.JAVASCRIPT, "node:22-alpine",
        ExternalLanguage.PYTHON, "python:3.12-slim",
        ExternalLanguage.SHELL, "ubuntu:24.04");

    private final ApplicationProperties applicationProperties;
    private final BiFunction<DockerOperatorSettings, Duration, DockerClient> dockerClientFactory;

    // Two constructors mean Spring can no longer pick one by elimination, and it fails the whole context at startup
    // rather than at first use.
    @Autowired
    public DockerTaskRunner(ApplicationProperties applicationProperties) {
        this(applicationProperties, DockerTaskRunner::createDockerClient);
    }

    /**
     * Takes the daemon connection as a parameter so a test can drive the whole lifecycle against a mock client. Every
     * guard in this class runs before a container exists, and the removal that matters runs after one does; neither is
     * observable through a runner that can only be exercised against a real daemon.
     */
    DockerTaskRunner(
        ApplicationProperties applicationProperties,
        BiFunction<DockerOperatorSettings, Duration, DockerClient> dockerClientFactory) {

        this.applicationProperties = applicationProperties;
        this.dockerClientFactory = dockerClientFactory;
    }

    @Override
    public String getType() {
        return DOCKER;
    }

    @Override
    public String getTitle() {
        return "Docker";
    }

    @Override
    public List<? extends ModifiableValueProperty<?, ?>> getProperties() {
        DockerOperatorSettings dockerOperatorSettings = DockerOperatorSettings.of(applicationProperties);

        List<String> allowedImages = dockerOperatorSettings.getAllowedImages();

        List<ModifiableOption<String>> imageOptions = allowedImages.stream()
            .map(image -> option(image, image))
            .toList();

        List<ModifiableValueProperty<?, ?>> properties = new ArrayList<>();

        // Optional because a blank image resolves to DEFAULT_IMAGES' entry for the action's language, the same way a
        // blank entrypoint resolves to that language's interpreter. The field is offered even when the allowlist is
        // empty: an operator who enabled the runner and named no image has to be able to see the image field to
        // understand why nothing runs, and validate rejects the execution with a message naming the configuration key
        // either way.
        properties.add(
            string(IMAGE)
                .label("Image")
                .description(
                    "The container image the task runs in. Defaults to the image for the action's language. Only " +
                        "images an operator has allowed can be run.")
                .options(imageOptions)
                .required(false));

        properties.add(
            string(PULL_POLICY)
                .label("Pull Policy")
                .description("When the image is pulled from its registry.")
                .options(
                    option("Always", PullPolicy.ALWAYS.name()),
                    option("If Not Present", PullPolicy.IF_NOT_PRESENT.name()),
                    option("Never", PullPolicy.NEVER.name()))
                .defaultValue(PullPolicy.IF_NOT_PRESENT.name())
                .required(false));

        properties.add(
            array(ENTRYPOINT)
                .label("Entrypoint")
                .description(
                    "Replaces the interpreter the source is handed to. Defaults to the interpreter for the action's " +
                        "language; the source file is appended either way.")
                .items(string())
                .required(false));

        properties.add(
            string(USER)
                .label("User")
                .description("The user the container runs as, as a name or a numeric uid[:gid].")
                .required(false));

        properties.add(
            number(CPU)
                .label("CPU")
                .description(
                    "How many CPUs the container may use, as a decimal fraction of one CPU. Defaults to the operator's "
                        +
                        "allowance, and cannot exceed the operator's maximum.")
                .required(false));

        properties.add(
            string(MEMORY)
                .label("Memory")
                .description(
                    "How much memory the container may use, in bytes or with a b, k, m or g suffix. Defaults to the " +
                        "operator's allowance, and cannot exceed the operator's maximum.")
                .required(false));

        // Offering host networking that validate would refuse would tell an operator about a capability the runner
        // does not have; ProcessTaskRunner hides its own operator-gated switch for the same reason. The options come
        // from the same NetworkMode set validate accepts, so the select and the boundary cannot drift apart.
        List<ModifiableOption<String>> networkModeOptions = NetworkMode.allowedBy(dockerOperatorSettings)
            .stream()
            .map(networkMode -> option(networkMode.getLabel(), networkMode.getValue()))
            .toList();

        properties.add(
            string(NETWORK_MODE)
                .label("Network Mode")
                .description("The network the container is attached to.")
                .options(networkModeOptions)
                .required(false));

        properties.add(
            array(EXTRA_HOSTS)
                .label("Extra Hosts")
                .description("Additional entries for the container's hosts file, each written as host:ip.")
                .items(string())
                .required(false));

        return properties;
    }

    @Override
    public Set<TaskRunnerCapability> getCapabilities() {
        return Set.of(
            TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.COMMANDS, TaskRunnerCapability.INPUT_FILES,
            TaskRunnerCapability.OUTPUT_FILES, TaskRunnerCapability.ENVIRONMENT);
    }

    @Override
    public void validate(TaskRunnerRequest request) {
        // The language decides the source file's name and the bootstrap appended to it, so a language neither knows is
        // unrunnable whatever image is named. It is checked first so an unrunnable language is reported as such rather
        // than as a missing image.
        String languageId = request.languageId();

        if (!TaskRunnerBootstrap.isSupported(languageId)) {
            throw new IllegalArgumentException(
                "Language '%s' cannot be executed by an external task runner".formatted(languageId));
        }

        DockerOperatorSettings dockerOperatorSettings = DockerOperatorSettings.of(applicationProperties);

        String image = resolveImage(request);

        if (!dockerOperatorSettings.isImageAllowed(image)) {
            throw new IllegalArgumentException(
                "Image '%s' is not allowed. An operator must add it to %s.".formatted(
                    image,
                    TaskRunnerOperatorFlag.getPropertyName(DOCKER, DockerOperatorSettings.ALLOWED_IMAGES_PROPERTY)));
        }

        resolveNetworkMode(request, dockerOperatorSettings);
        resolvePullPolicy(request);
        resolveEntrypoint(request);
        resolveNanoCpus(request, dockerOperatorSettings);
        resolveMemoryBytes(request, dockerOperatorSettings);
        resolvePidsLimit(dockerOperatorSettings);
    }

    /**
     * The network the container is attached to: the mode the request names, or the first the operator allows.
     *
     * <p>
     * Accepts only the modes {@link NetworkMode} names, and only those the operator has enabled. Anything else is
     * refused rather than passed on, because what the daemon does with an unrecognised value is not to reject it: a
     * network name attaches the container to that network - ByteChef's own compose network, where the database answers
     * on a password written in the same file - and {@code container:<id>} joins another container's namespace outright,
     * inheriting host networking from it without the word {@code host} ever appearing. The image allowlist mitigates
     * none of that; every image carrying an interpreter can open a socket.
     *
     * <p>
     * A request naming no mode resolves to one here rather than being left off the host configuration, because the
     * daemon's own answer to a missing {@code NetworkMode} is {@code bridge} - so an operator who allowed only
     * {@code none} got {@code bridge} for every workflow that simply did not fill the field in, which is the one case
     * the setting exists for. The resolved mode is the first of {@link NetworkMode}'s own declaration order the
     * operator allows, not the first they wrote: with the default allowlist that is {@code bridge}, exactly what the
     * daemon was doing before, and {@code host} can only be reached this way by an operator who allowed nothing else.
     */
    private static NetworkMode resolveNetworkMode(
        TaskRunnerRequest request, DockerOperatorSettings dockerOperatorSettings) {

        List<NetworkMode> allowedNetworkModes = NetworkMode.allowedBy(dockerOperatorSettings);

        String value = getNetworkMode(request);

        if (value == null) {
            if (allowedNetworkModes.isEmpty()) {
                throw new IllegalArgumentException(
                    "The docker runner has no network mode it may use. An operator must name one in %s.".formatted(
                        TaskRunnerOperatorFlag.getPropertyName(
                            DOCKER, DockerOperatorSettings.ALLOWED_NETWORK_MODES_PROPERTY)));
            }

            return allowedNetworkModes.getFirst();
        }

        NetworkMode networkMode = NetworkMode.of(value);

        if (networkMode == NetworkMode.HOST && !dockerOperatorSettings.isHostNetworkAllowed()) {
            throw new IllegalArgumentException(
                "The docker runner's '%s' network mode is not enabled. An operator must enable it with %s=true."
                    .formatted(
                        NetworkMode.HOST.getValue(),
                        TaskRunnerOperatorFlag.getPropertyName(
                            DOCKER, DockerOperatorSettings.ALLOW_HOST_NETWORK_PROPERTY)));
        }

        if (networkMode == null || !allowedNetworkModes.contains(networkMode)) {
            List<String> allowedValues = allowedNetworkModes.stream()
                .map(NetworkMode::getValue)
                .toList();

            throw new IllegalArgumentException(
                "The docker runner cannot use the network mode '%s'; it accepts %s.".formatted(
                    value, String.join(", ", allowedValues)));
        }

        return networkMode;
    }

    @Override
    public TaskRunnerResult run(TaskRunnerRequest request) {
        validate(request);

        try (TaskRunnerWorkingDirectory workingDirectory = TaskRunnerWorkingDirectory.create(request)) {
            return run(request, workingDirectory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();

            throw new IllegalStateException("Interrupted while running the docker task runner", exception);
        }
    }

    private TaskRunnerResult run(TaskRunnerRequest request, TaskRunnerWorkingDirectory workingDirectory)
        throws IOException, InterruptedException {

        Duration requestTimeout = request.timeout();
        Duration timeout = requestTimeout == null ? DEFAULT_TIMEOUT : requestTimeout;

        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        String languageId = request.languageId();
        String script = request.script();

        String source = script != null
            ? TaskRunnerBootstrap.append(languageId, script, DOCKER)
            : String.join(System.lineSeparator(), request.commands()) + System.lineSeparator();

        String sourceFileName = TaskRunnerBootstrap.sourceFileName(languageId);

        workingDirectory.writeSourceFile(sourceFileName, source);

        DockerOperatorSettings dockerOperatorSettings = DockerOperatorSettings.of(applicationProperties);

        // Resolved the same way validate resolved it, so the image the allowlist accepted is the image that is pulled
        // and run - a second rule here would be a rule the allowlist never saw.
        String image = resolveImage(request);

        try (DockerClient dockerClient = dockerClientFactory.apply(dockerOperatorSettings, timeout)) {
            // Nothing has been created on the daemon yet, so every failure from here to createContainer - an
            // unreachable daemon, a registry that hangs past the deadline, a rejected create - leaves nothing to
            // remove.
            pullImage(dockerClient, image, request, timeout, deadlineNanos);

            checkDeadline(deadlineNanos, timeout, "creating the container");

            String containerId = createContainer(
                dockerClient, image, request, dockerOperatorSettings, workingDirectory, sourceFileName);

            try {
                return run(dockerClient, containerId, request, workingDirectory, timeout, deadlineNanos);
            } finally {
                removeContainer(dockerClient, containerId);
            }
        }
    }

    private static TaskRunnerResult run(
        DockerClient dockerClient, String containerId, TaskRunnerRequest request,
        TaskRunnerWorkingDirectory workingDirectory, Duration timeout, long deadlineNanos)
        throws IOException, InterruptedException {

        copyWorkingDirectoryIn(dockerClient, containerId, workingDirectory);

        checkDeadline(deadlineNanos, timeout, "starting the container");

        try (StartContainerCmd startContainerCmd = dockerClient.startContainerCmd(containerId)) {
            startContainerCmd.exec();
        }

        BoundedOutputCapture stdout = new BoundedOutputCapture();
        BoundedOutputCapture stderr = new BoundedOutputCapture();

        LogCallback logCallback = attachLogs(dockerClient, containerId, stdout, stderr);

        ExitResult exitResult;

        // The tails are what make the timeout, the missing exit code and the non-zero exit messages useful, so the
        // wait reports its outcome as a value and every one of those messages is formatted after the log stream has
        // been drained - including on the interruption that leaves this method through the same finally. Formatting
        // them inside the wait drained the stream after the throw that needed it.
        try {
            exitResult = awaitExit(dockerClient, containerId, deadlineNanos);
        } finally {
            drainLogs(logCallback);
        }

        if (!exitResult.exited()) {
            throw new IllegalStateException(
                "The docker task runner timed out after %s; stdout tail: %s; stderr tail: %s".formatted(
                    timeout, stdout.get(), stderr.get()));
        }

        Integer statusCode = exitResult.statusCode();

        if (statusCode == null) {
            throw new IllegalStateException(
                "The container reported no exit code; stdout tail: %s; stderr tail: %s".formatted(
                    stdout.get(), stderr.get()));
        }

        int exitCode = statusCode;

        if (exitCode != 0) {
            throw new IllegalStateException(
                "The container exited with code %d; stdout tail: %s; stderr tail: %s".formatted(
                    exitCode, stdout.get(), stderr.get()));
        }

        checkDeadline(deadlineNanos, timeout, "collecting the output");

        copyOutputOut(dockerClient, containerId, workingDirectory);

        return new TaskRunnerResult(
            TaskRunnerOutputs.readOutputJson(workingDirectory), exitCode, stdout.get(), stderr.get(),
            TaskRunnerOutputs.collectOutputFiles(request, workingDirectory));
    }

    private static LogCallback attachLogs(
        DockerClient dockerClient, String containerId, BoundedOutputCapture stdout, BoundedOutputCapture stderr) {

        LogCallback logCallback = new LogCallback(stdout, stderr);

        // Attached after the start rather than before it: the daemon retains a container's log from its first byte and
        // a following read replays it, so nothing written between the start and this call is lost.
        try (LogContainerCmd logContainerCmd = dockerClient.logContainerCmd(containerId)) {
            logContainerCmd.withFollowStream(true);
            logContainerCmd.withStdOut(true);
            logContainerCmd.withStdErr(true);
            logContainerCmd.withTailAll();

            logContainerCmd.exec(logCallback);
        } catch (RuntimeException exception) {
            // The callback holds its two logging streams from construction, and an attach that throws never reaches
            // the drain that would close them. The container is removed either way; the streams are this method's own.
            closeQuietly(logCallback);

            throw exception;
        }

        return logCallback;
    }

    private static void closeQuietly(LogCallback logCallback) {
        try {
            logCallback.close();
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not close the container's log stream", exception);
        }
    }

    /**
     * Waits for the container's exit code within what is left of the deadline, reporting both outcomes as values so the
     * caller can drain the log stream before it turns either into a failure.
     */
    private static ExitResult awaitExit(DockerClient dockerClient, String containerId, long deadlineNanos)
        throws IOException, InterruptedException {

        try (WaitCallback waitCallback = new WaitCallback();
            WaitContainerCmd waitContainerCmd = dockerClient.waitContainerCmd(containerId)) {

            waitContainerCmd.exec(waitCallback);

            boolean exited = waitCallback.awaitCompletion(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);

            return new ExitResult(exited, waitCallback.getStatusCode());
        }
    }

    /**
     * Waits a bounded grace for the log stream to reach its end, then closes it whether it did or not.
     *
     * <p>
     * The stream ends when the container is gone, which the exit has usually already made true; the grace covers the
     * gap between the exit code arriving and the last frame doing so. It never throws - it runs in a {@code finally}
     * covering the wait, and a failure to collect trailing output must not replace the failure that got here.
     */
    private static void drainLogs(LogCallback logCallback) {
        try {
            boolean drained = logCallback.awaitCompletion(LOG_DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);

            if (!drained) {
                log.warn(
                    "The docker task runner stopped waiting for the container's log stream after {}; the captured " +
                        "output may be incomplete",
                    LOG_DRAIN_GRACE);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();

            log.debug("Interrupted while draining the container's log stream", exception);
        } catch (RuntimeException exception) {
            log.warn("Could not drain the container's log stream", exception);
        }

        closeQuietly(logCallback);
    }

    private static void pullImage(
        DockerClient dockerClient, String image, TaskRunnerRequest request, Duration timeout, long deadlineNanos)
        throws IOException, InterruptedException {

        PullPolicy pullPolicy = resolvePullPolicy(request);

        if (pullPolicy == PullPolicy.NEVER) {
            return;
        }

        if (pullPolicy == PullPolicy.IF_NOT_PRESENT && isImagePresent(dockerClient, image)) {
            return;
        }

        checkDeadline(deadlineNanos, timeout, "pulling the image");

        // A registry that accepts the connection and then stops answering holds this open indefinitely, which is why
        // the whole lifecycle rather than the wait alone is bounded.
        try (PullImageResultCallback pullImageResultCallback = new PullImageResultCallback();
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image)) {

            pullImageCmd.exec(pullImageResultCallback);

            boolean pulled = pullImageResultCallback.awaitCompletion(
                remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);

            if (!pulled) {
                throw new IllegalStateException(
                    "The docker task runner timed out after %s pulling image '%s'".formatted(timeout, image));
            }
        }
    }

    private static boolean isImagePresent(DockerClient dockerClient, String image) {
        try (InspectImageCmd inspectImageCmd = dockerClient.inspectImageCmd(image)) {
            inspectImageCmd.exec();

            return true;
        } catch (NotFoundException exception) {
            log.debug("Image {} is not present on the daemon", image, exception);

            return false;
        }
    }

    private static String createContainer(
        DockerClient dockerClient, String image, TaskRunnerRequest request,
        DockerOperatorSettings dockerOperatorSettings, TaskRunnerWorkingDirectory workingDirectory,
        String sourceFileName) {

        try (CreateContainerCmd createContainerCmd = dockerClient.createContainerCmd(image)) {
            createContainerCmd.withHostConfig(createHostConfig(request, dockerOperatorSettings));
            createContainerCmd.withWorkingDir(CONTAINER_DIRECTORY);
            createContainerCmd.withEntrypoint(resolveEntrypoint(request));
            createContainerCmd.withCmd(CONTAINER_DIRECTORY + "/" + sourceFileName);
            createContainerCmd.withEnv(createEnvironment(request, workingDirectory));
            createContainerCmd.withAttachStdout(true);
            createContainerCmd.withAttachStderr(true);

            // A TTY would merge the two streams into one, and the result keeps them apart.
            createContainerCmd.withTty(false);

            String user = getString(request, USER);

            if (user != null) {
                createContainerCmd.withUser(user);
            }

            CreateContainerResponse createContainerResponse = createContainerCmd.exec();

            return createContainerResponse.getId();
        }
    }

    /**
     * Builds the host configuration, which carries no binds at all - see the class javadoc on {@code volumes}.
     *
     * <p>
     * The network mode, the CPU allowance, the memory allowance and the PID ceiling are all unconditional: every one of
     * them resolves to an operator's value when the request names none, so there is no path through this method that
     * leaves a container bounded only by the host.
     */
    private static HostConfig createHostConfig(
        TaskRunnerRequest request, DockerOperatorSettings dockerOperatorSettings) {

        HostConfig hostConfig = HostConfig.newHostConfig();

        // validate ran first and refuses every mode outside the closed set, so what reaches the daemon is the set's own
        // canonical value, not the string as typed.
        NetworkMode networkMode = resolveNetworkMode(request, dockerOperatorSettings);

        hostConfig.withNetworkMode(networkMode.getValue());
        hostConfig.withNanoCPUs(resolveNanoCpus(request, dockerOperatorSettings));
        hostConfig.withMemory(resolveMemoryBytes(request, dockerOperatorSettings));
        hostConfig.withPidsLimit(resolvePidsLimit(dockerOperatorSettings));

        List<String> extraHosts = request.runnerParameters()
            .getList(EXTRA_HOSTS, String.class, List.of());

        if (!extraHosts.isEmpty()) {
            hostConfig.withExtraHosts(extraHosts.toArray(new String[0]));
        }

        return hostConfig;
    }

    /**
     * The container's environment, with the three {@code BYTECHEF_*} names put back last for the reason
     * {@code ProcessTaskRunner} puts them back last: they are the contract the bootstrap reads its input and writes its
     * output through, so a declared entry of the same name would redirect output collection rather than configure the
     * execution.
     *
     * <p>
     * There is no inherited environment to clear here. A container starts from the image's environment, and the server
     * process's own never reaches it - which is the guarantee {@code ProcessTaskRunner} has to build by hand.
     *
     * <p>
     * {@code HOME} is seeded first, so it is the container's working directory unless a declared {@code env} entry
     * replaces it - the same order {@code ProcessTaskRunner} puts its own re-seeded {@code HOME} in. Left to the image
     * it is {@code /root}, or {@code /} for a numeric {@code user} that appears in no {@code /etc/passwd}, and the
     * interpreters that write caches relative to it then fail on a permission error the same action never sees under
     * the process runner. {@code ContainerArchive} already widens the working directory's modes for exactly this class
     * of problem.
     */
    private static List<String> createEnvironment(
        TaskRunnerRequest request, TaskRunnerWorkingDirectory workingDirectory) {

        Map<String, String> environment = new LinkedHashMap<>();

        environment.put("HOME", CONTAINER_DIRECTORY);

        environment.putAll(request.env());

        String hostPath = workingDirectory.getPath()
            .toString();

        for (Map.Entry<String, String> entry : workingDirectory.getEnvironment()
            .entrySet()) {

            environment.put(entry.getKey(), toContainerPath(hostPath, entry.getValue()));
        }

        return environment.entrySet()
            .stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .toList();
    }

    /**
     * Rewrites a host path under the working directory onto the container's copy of it. The names come from the working
     * directory itself, so this class never restates them and cannot drift from them.
     */
    private static String toContainerPath(String hostPath, String value) {
        if (!value.startsWith(hostPath)) {
            return value;
        }

        String relative = value.substring(hostPath.length())
            .replace('\\', '/');

        return CONTAINER_DIRECTORY + relative;
    }

    private static void copyWorkingDirectoryIn(
        DockerClient dockerClient, String containerId, TaskRunnerWorkingDirectory workingDirectory)
        throws IOException {

        Path path = workingDirectory.getPath();

        try (InputStream inputStream = ContainerArchive.toTar(path);
            CopyArchiveToContainerCmd copyArchiveToContainerCmd = dockerClient.copyArchiveToContainerCmd(containerId)) {

            copyArchiveToContainerCmd.withRemotePath(CONTAINER_DIRECTORY);
            copyArchiveToContainerCmd.withTarInputStream(inputStream);

            copyArchiveToContainerCmd.exec();
        }
    }

    /**
     * Extracts the container's output directory back over the host's.
     *
     * <p>
     * The archive names its entries relative to the copied resource's parent, so they arrive prefixed with
     * {@code output/} and the extraction target is the working directory rather than its output directory. Everything
     * the guest can reach that way is inside the working directory - which {@code ContainerArchive} enforces and this
     * execution is about to delete - and only what is under {@code output/} is ever read back.
     *
     * <p>
     * A missing output directory is not a failure. An execution is free to remove it, and the collectors already treat
     * an absent one as an empty result.
     */
    private static void copyOutputOut(
        DockerClient dockerClient, String containerId, TaskRunnerWorkingDirectory workingDirectory)
        throws IOException {

        try (CopyArchiveFromContainerCmd copyArchiveFromContainerCmd = dockerClient.copyArchiveFromContainerCmd(
            containerId, CONTAINER_OUTPUT_DIRECTORY)) {

            try (InputStream inputStream = copyArchiveFromContainerCmd.exec()) {
                ContainerArchive.extractTar(inputStream, workingDirectory.getPath());
            }
        } catch (NotFoundException exception) {
            log.warn("The container left no {} directory, so the execution produced no output",
                CONTAINER_OUTPUT_DIRECTORY, exception);
        }
    }

    /**
     * Removes the container and anything it wrote, on every path out of the execution.
     *
     * <p>
     * It never throws. It runs in a {@code finally} that also covers the failure paths, and a daemon that refuses the
     * removal must not replace the exception explaining why the task failed with one about cleaning up after it.
     */
    private static void removeContainer(DockerClient dockerClient, String containerId) {
        try (RemoveContainerCmd removeContainerCmd = dockerClient.removeContainerCmd(containerId)) {
            removeContainerCmd.withForce(true);
            removeContainerCmd.withRemoveVolumes(true);

            removeContainerCmd.exec();
        } catch (RuntimeException exception) {
            log.warn("Could not remove the container {}", containerId, exception);
        }
    }

    private static void checkDeadline(long deadlineNanos, Duration timeout, String stage) {
        if (System.nanoTime() - deadlineNanos >= 0) {
            throw new IllegalStateException(
                "The docker task runner timed out after %s while %s".formatted(timeout, stage));
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();

        return remainingNanos > 0 ? TimeUnit.NANOSECONDS.toMillis(remainingNanos) : 0;
    }

    /**
     * The image the request names, or the one {@link #DEFAULT_IMAGES} holds for its language.
     *
     * <p>
     * Called by {@code validate} and again by {@code run}, so the image the allowlist accepted is the image the daemon
     * is handed.
     */
    private static String resolveImage(TaskRunnerRequest request) {
        String image = getString(request, IMAGE);

        if (image != null) {
            return image;
        }

        String languageId = request.languageId();

        String defaultImage = DEFAULT_IMAGES.get(ExternalLanguage.of(languageId));

        if (defaultImage == null) {
            throw new IllegalArgumentException(
                "The docker runner has no default image for language '%s'; name one on the task runner"
                    .formatted(languageId));
        }

        return defaultImage;
    }

    @Nullable
    private static String getNetworkMode(TaskRunnerRequest request) {
        return getString(request, NETWORK_MODE);
    }

    /**
     * A runner parameter as a trimmed value, or null when it is absent or blank. Trimming once here is what keeps the
     * value {@code validate} compares against the allowlist identical to the value {@code run} hands the daemon.
     */
    @Nullable
    private static String getString(TaskRunnerRequest request, String name) {
        String value = request.runnerParameters()
            .getString(name);

        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> resolveEntrypoint(TaskRunnerRequest request) {
        List<String> entrypoint = request.runnerParameters()
            .getList(ENTRYPOINT, String.class, List.of());

        if (!entrypoint.isEmpty()) {
            return entrypoint;
        }

        String languageId = request.languageId();

        List<String> defaultEntrypoint = DEFAULT_ENTRYPOINTS.get(ExternalLanguage.of(languageId));

        if (defaultEntrypoint == null) {
            throw new IllegalArgumentException(
                "The docker runner has no default entrypoint for language '%s'; set one on the task runner"
                    .formatted(languageId));
        }

        return defaultEntrypoint;
    }

    private static PullPolicy resolvePullPolicy(TaskRunnerRequest request) {
        String pullPolicy = getString(request, PULL_POLICY);

        if (pullPolicy == null) {
            return PullPolicy.IF_NOT_PRESENT;
        }

        for (PullPolicy candidate : PullPolicy.values()) {
            if (candidate.name()
                .equalsIgnoreCase(pullPolicy)) {

                return candidate;
            }
        }

        throw new IllegalArgumentException(
            "The docker runner does not know the pull policy '%s'".formatted(pullPolicy));
    }

    /**
     * The container's CPU allowance: the request's own, the operator's default when it names none, and never above the
     * operator's maximum.
     *
     * <p>
     * A request above the maximum is refused rather than clamped, following the image allowlist: an execution silently
     * given a quarter of the CPU it asked for does not fail here, it fails much later as a timeout, in a message that
     * says nothing about the ceiling that caused it. The operator's own default is clamped rather than refused, because
     * a default above the maximum is a configuration mistake no workflow can fix and refusing every execution over it
     * would be a worse answer than honouring the maximum they also wrote.
     */
    private static long resolveNanoCpus(TaskRunnerRequest request, DockerOperatorSettings dockerOperatorSettings) {
        long maxNanoCpus = parseOperatorNanoCpus(
            dockerOperatorSettings.getMaxCpu(), DockerOperatorSettings.MAX_CPU_PROPERTY);

        Double cpu = request.runnerParameters()
            .getDouble(CPU);

        if (cpu == null) {
            long defaultNanoCpus = parseOperatorNanoCpus(
                dockerOperatorSettings.getDefaultCpu(), DockerOperatorSettings.DEFAULT_CPU_PROPERTY);

            return Math.min(defaultNanoCpus, maxNanoCpus);
        }

        if (cpu <= 0) {
            throw new IllegalArgumentException("The docker runner's cpu limit must be greater than zero");
        }

        long nanoCpus = Math.round(cpu * NANO_CPUS_PER_CPU);

        if (nanoCpus > maxNanoCpus) {
            throw new IllegalArgumentException(
                "The docker runner's cpu limit '%s' is above the maximum an operator allows. An operator must raise %s."
                    .formatted(
                        cpu,
                        TaskRunnerOperatorFlag.getPropertyName(DOCKER, DockerOperatorSettings.MAX_CPU_PROPERTY)));
        }

        return nanoCpus;
    }

    private static long parseOperatorNanoCpus(String value, String propertyName) {
        double cpu;

        try {
            cpu = Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "The docker runner cannot read '%s' as a cpu limit; an operator must correct %s.".formatted(
                    value, TaskRunnerOperatorFlag.getPropertyName(DOCKER, propertyName)),
                exception);
        }

        if (!(cpu > 0)) {
            throw new IllegalArgumentException(
                "The docker runner's %s must be greater than zero.".formatted(
                    TaskRunnerOperatorFlag.getPropertyName(DOCKER, propertyName)));
        }

        return Math.round(cpu * NANO_CPUS_PER_CPU);
    }

    /**
     * The container's memory allowance, resolved and bounded the way {@link #resolveNanoCpus} resolves and bounds the
     * CPU allowance.
     */
    private static long resolveMemoryBytes(TaskRunnerRequest request, DockerOperatorSettings dockerOperatorSettings) {
        long maxMemoryBytes = parseOperatorMemoryBytes(
            dockerOperatorSettings.getMaxMemory(), DockerOperatorSettings.MAX_MEMORY_PROPERTY);

        String memory = getString(request, MEMORY);

        if (memory == null) {
            long defaultMemoryBytes = parseOperatorMemoryBytes(
                dockerOperatorSettings.getDefaultMemory(), DockerOperatorSettings.DEFAULT_MEMORY_PROPERTY);

            return Math.min(defaultMemoryBytes, maxMemoryBytes);
        }

        long memoryBytes = parseMemoryBytes(memory);

        if (memoryBytes > maxMemoryBytes) {
            throw new IllegalArgumentException(
                ("The docker runner's memory limit '%s' is above the maximum an operator allows. An operator must " +
                    "raise %s.").formatted(
                        memory,
                        TaskRunnerOperatorFlag.getPropertyName(DOCKER, DockerOperatorSettings.MAX_MEMORY_PROPERTY)));
        }

        return memoryBytes;
    }

    private static long parseOperatorMemoryBytes(String value, String propertyName) {
        try {
            return parseMemoryBytes(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "The docker runner cannot read '%s' as a memory limit; an operator must correct %s.".formatted(
                    value, TaskRunnerOperatorFlag.getPropertyName(DOCKER, propertyName)),
                exception);
        }
    }

    /**
     * The number of processes the container may hold open.
     *
     * <p>
     * Operator-only, and unconditional. Docker reads a negative limit as "unlimited"; that is refused here rather than
     * passed on, because the whole point of this ceiling is that no configuration and no workflow produces a container
     * without one.
     */
    private static long resolvePidsLimit(DockerOperatorSettings dockerOperatorSettings) {
        String value = dockerOperatorSettings.getPidsLimit();

        long pidsLimit;

        try {
            pidsLimit = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "The docker runner cannot read '%s' as a pids limit; an operator must correct %s.".formatted(
                    value,
                    TaskRunnerOperatorFlag.getPropertyName(DOCKER, DockerOperatorSettings.PIDS_LIMIT_PROPERTY)),
                exception);
        }

        if (pidsLimit <= 0) {
            throw new IllegalArgumentException(
                "The docker runner's %s must be greater than zero; a container is never left unbounded.".formatted(
                    TaskRunnerOperatorFlag.getPropertyName(DOCKER, DockerOperatorSettings.PIDS_LIMIT_PROPERTY)));
        }

        return pidsLimit;
    }

    private static long parseMemoryBytes(String memory) {
        Matcher matcher = MEMORY_PATTERN.matcher(memory);

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "The docker runner cannot read '%s' as a memory limit; write bytes, or a number with a b, k, m or g suffix"
                    .formatted(memory));
        }

        long value;

        try {
            value = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "The docker runner's memory limit '%s' is too large".formatted(memory), exception);
        }

        long multiplier = memoryMultiplier(matcher.group(2));

        if (value <= 0 || value > Long.MAX_VALUE / multiplier) {
            throw new IllegalArgumentException(
                "The docker runner's memory limit '%s' is out of range".formatted(memory));
        }

        return value * multiplier;
    }

    private static long memoryMultiplier(@Nullable String suffix) {
        if (suffix == null) {
            return 1L;
        }

        return switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1024L;
            case "m" -> 1024L * 1024;
            case "g" -> 1024L * 1024 * 1024;
            default -> 1L;
        };
    }

    /**
     * Builds the daemon connection, with a response timeout of the execution's own timeout.
     *
     * <p>
     * Without one, every synchronous call to the daemon is unbounded: a daemon that accepts the connection and then
     * stops answering during the copy-out holds the task thread past any ceiling the workflow asked for, and the
     * deadline checks around those calls cannot help - they run before the call, not during it. The execution's timeout
     * is the right value rather than a fixed one, because the wait for the exit code is itself a response that does not
     * arrive until the container exits: a shorter timeout would break a legitimately long-running container, and a read
     * still blocked after the execution's own timeout is already past the deadline the workflow asked for.
     */
    private static DockerClient createDockerClient(DockerOperatorSettings dockerOperatorSettings, Duration timeout) {
        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();

        String host = dockerOperatorSettings.getHost();

        // An unset host leaves docker-java on its own resolution - DOCKER_HOST, then the platform default socket -
        // which is what an operator who wrote no host meant.
        if (!host.isBlank()) {
            builder.withDockerHost(host);
        }

        DefaultDockerClientConfig dockerClientConfig = builder.build();

        DockerHttpClient dockerHttpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(dockerClientConfig.getDockerHost())
            .sslConfig(dockerClientConfig.getSSLConfig())
            .responseTimeout(timeout)
            .build();

        return DockerClientImpl.getInstance(dockerClientConfig, dockerHttpClient);
    }

    /**
     * The outcome of the wait for the container's exit code: whether it exited within the deadline at all, and the code
     * it reported if it did.
     */
    private record ExitResult(boolean exited, @Nullable Integer statusCode) {
    }

    /**
     * The closed set of network modes a workflow may ask for, and the one place both the select's options and
     * {@link #validateNetworkMode}'s accepted set are derived from.
     *
     * <p>
     * Closed rather than "anything but host", because what the daemon does with an unrecognised value is not to reject
     * it. A network name attaches the container to that network - {@code bytechef_default} is the shipped compose
     * network, where Postgres answers on a password written in the same file - and {@code container:<id>} joins another
     * container's network namespace outright, reaching whatever it has bound to its loopback and inheriting host
     * networking transitively when that container was started with it. None of those carry the string {@code host}, so
     * a check that only looks for {@code host} passes every one of them through to {@code withNetworkMode}.
     *
     * <p>
     * Which of the three an operator permits is {@code allowed-network-modes}' decision, and {@code allow-host-network}
     * gates {@code host} on top of it - a mode has to survive both to be offered or accepted. Declaration order is
     * meaningful: it is the order a request naming no mode resolves through, which is why {@code bridge} is first and
     * {@code host} last.
     */
    private enum NetworkMode {

        BRIDGE("Bridge", "bridge", false),
        NONE("None", "none", false),
        HOST("Host", "host", true);

        private final String label;
        private final String value;
        private final boolean operatorGated;

        NetworkMode(String label, String value, boolean operatorGated) {
            this.label = label;
            this.value = value;
            this.operatorGated = operatorGated;
        }

        static List<NetworkMode> allowedBy(DockerOperatorSettings dockerOperatorSettings) {
            return Arrays.stream(values())
                .filter(networkMode -> dockerOperatorSettings.isNetworkModeAllowed(networkMode.value))
                .filter(networkMode -> !networkMode.operatorGated || dockerOperatorSettings.isHostNetworkAllowed())
                .toList();
        }

        @Nullable
        static NetworkMode of(String value) {
            for (NetworkMode networkMode : values()) {
                if (networkMode.value.equalsIgnoreCase(value)) {
                    return networkMode;
                }
            }

            return null;
        }

        String getLabel() {
            return label;
        }

        String getValue() {
            return value;
        }
    }

    /**
     * When the image is fetched from its registry. The names are Kestra's, because these are the workflows Kestra users
     * are porting and a policy that reads the same means the same.
     */
    private enum PullPolicy {

        ALWAYS, IF_NOT_PRESENT, NEVER
    }

    /**
     * Collects the container's exit code.
     *
     * <p>
     * Written out rather than reusing {@code WaitContainerResultCallback} so the wait is
     * {@link ResultCallback.Adapter#awaitCompletion(long, java.util.concurrent.TimeUnit)}, which reports a timeout as a
     * value and an interruption as {@code InterruptedException}. The convenience method's own timed wait converts one
     * into an exception and swallows the other, and both of those are paths the container has to be removed on.
     */
    private static final class WaitCallback extends ResultCallback.Adapter<WaitResponse> {

        private final AtomicReference<Integer> statusCode = new AtomicReference<>();

        @Override
        public void onNext(WaitResponse waitResponse) {
            statusCode.set(waitResponse.getStatusCode());
        }

        @Nullable
        Integer getStatusCode() {
            return statusCode.get();
        }
    }

    /**
     * Captures the container's two streams and tees them to the guest logger.
     *
     * <p>
     * What the task stores is bounded, so past the capture's limit the logs are the only place the middle of a long
     * stream still exists - the same division of labour {@code ProcessTaskRunner} makes between its capture and its
     * drain threads.
     */
    private static final class LogCallback extends ResultCallback.Adapter<Frame> {

        private final BoundedOutputCapture stdout;
        private final BoundedOutputCapture stderr;
        private final GuestLoggingOutputStream stdoutLoggingOutputStream;
        private final GuestLoggingOutputStream stderrLoggingOutputStream;

        LogCallback(BoundedOutputCapture stdout, BoundedOutputCapture stderr) {
            this.stdout = stdout;
            this.stderr = stderr;

            this.stdoutLoggingOutputStream = new GuestLoggingOutputStream(line -> {
                if (log.isInfoEnabled()) {
                    log.info("[docker] {}", line);
                }
            });
            this.stderrLoggingOutputStream = new GuestLoggingOutputStream(line -> log.warn("[docker] {}", line));
        }

        @Override
        public void onNext(Frame frame) {
            byte[] payload = frame.getPayload();

            if (payload == null) {
                return;
            }

            StreamType streamType = frame.getStreamType();

            if (streamType == StreamType.STDERR) {
                append(stderr, stderrLoggingOutputStream, payload);
            } else {
                append(stdout, stdoutLoggingOutputStream, payload);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                stdoutLoggingOutputStream.close();
                stderrLoggingOutputStream.close();
            }
        }

        private static void append(
            BoundedOutputCapture capture, GuestLoggingOutputStream loggingOutputStream, byte[] payload) {

            capture.append(payload, payload.length);

            try {
                loggingOutputStream.write(payload, 0, payload.length);
            } catch (IOException exception) {
                log.debug("Could not tee the container's output to the log", exception);
            }
        }
    }
}
