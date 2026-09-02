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

import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.runner.TaskRunnerOperatorFlag;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The operator settings that gate the Docker task runner: which daemon it talks to, which images it may run, which
 * networks it may attach to, how much of the host one container may take, and whether it may relax container isolation.
 *
 * <p>
 * {@link ApplicationProperties.Script.Runner#getProperties()} is a flat {@code Map<String, String>}, not a structured
 * type, so {@code allowed-images} cannot arrive as a list - an operator who writes a YAML list under {@code properties}
 * still gets one comma-separated string back. This class is where that string is parsed, once, into the trimmed and
 * blank-filtered list every other check compares against. {@code allowed-network-modes} has exactly the same shape for
 * the same reason, and is matched the same way.
 *
 * <p>
 * The image allowlist is fail-closed, and its empty state is CLOSED rather than open: an operator who enables the
 * Docker runner without naming an image gets no runnable image, not an unrestricted one. Silently reading an absent or
 * unparseable allowlist as "everything permitted" would turn one missing config line into arbitrary image execution, so
 * {@link #isImageAllowed(String)} returns {@code false} for every image until at least one is named. Matching is exact
 * string equality against that list - no prefix matching, no wildcards, no registry normalisation. An operator who
 * wants two tags lists two tags.
 *
 * <p>
 * The network-mode allowlist is the one list whose <em>absent</em> state is a value rather than a closed set:
 * {@link #DEFAULT_ALLOWED_NETWORK_MODES} names every mode the runner knows, and {@code allow-host-network} keeps
 * {@code host} out of the effective set on top of it, so adding the key breaks no deployment. It is still an allowlist
 * - a token naming no mode the runner knows matches nothing, so an operator who writes only unknown tokens gets a
 * runner that refuses every execution rather than one that accepts anything.
 *
 * <p>
 * The four resource ceilings - {@code default-cpu}, {@code max-cpu}, {@code default-memory}, {@code max-memory} - and
 * {@code pids-limit} are handed back <strong>as the operator wrote them</strong> and parsed by
 * {@code DockerTaskRunner}, with the same grammar it applies to a workflow's own {@code cpu} and {@code memory}. Two
 * things follow, both deliberate. A malformed ceiling refuses every execution with a message naming its key rather than
 * quietly becoming "unlimited" - the fail-closed direction for a ceiling is refusal, not absence. And the parse happens
 * on the execution path rather than in {@code of}, which the editor calls to assemble the runner's properties: a
 * half-written ceiling must not take the editor down with it.
 *
 * <p>
 * The two boolean escape hatches - {@code allow-host-network} and {@code allow-volume-mounts} - are read through
 * {@link TaskRunnerOperatorFlag} rather than parsed here, so they fail the same way as every other runner's operator
 * flag on a half-written configuration.
 *
 * @author Ivica Cardic
 */
public final class DockerOperatorSettings {

    /**
     * The configuration key naming the images that may be run. Exposed because the runner's rejection messages tell an
     * operator which key to edit, and a key named in two places is a key that can be renamed in one of them.
     */
    public static final String ALLOWED_IMAGES_PROPERTY = "allowed-images";

    /**
     * The configuration key that relaxes container isolation onto the host's network stack. Exposed for the same reason
     * as {@link #ALLOWED_IMAGES_PROPERTY}.
     */
    public static final String ALLOW_HOST_NETWORK_PROPERTY = "allow-host-network";

    /**
     * The configuration key naming the network modes a workflow may ask for. Exposed for the same reason as
     * {@link #ALLOWED_IMAGES_PROPERTY}.
     */
    public static final String ALLOWED_NETWORK_MODES_PROPERTY = "allowed-network-modes";

    /**
     * The configuration key holding the CPU allowance a container gets when its workflow asks for none.
     */
    public static final String DEFAULT_CPU_PROPERTY = "default-cpu";

    /**
     * The configuration key holding the largest CPU allowance a workflow may ask for.
     */
    public static final String MAX_CPU_PROPERTY = "max-cpu";

    /**
     * The configuration key holding the memory allowance a container gets when its workflow asks for none.
     */
    public static final String DEFAULT_MEMORY_PROPERTY = "default-memory";

    /**
     * The configuration key holding the largest memory allowance a workflow may ask for.
     */
    public static final String MAX_MEMORY_PROPERTY = "max-memory";

    /**
     * The configuration key holding the number of processes one container may hold open. There is no workflow-facing
     * property for it: a PID ceiling is the one limit whose absence a workflow author would never notice and an
     * operator can never recover from, so it is set for every container and only an operator moves it.
     */
    public static final String PIDS_LIMIT_PROPERTY = "pids-limit";

    /**
     * The network modes a workflow may ask for when the operator has named none: every mode the runner knows.
     *
     * <p>
     * The <em>effective</em> default is {@code none} and {@code bridge}, because {@link #ALLOW_HOST_NETWORK_PROPERTY}
     * gates {@code host} on top of this list and is itself off by default - so an operator upgrading into this version
     * keeps exactly the modes they had, including {@code host} if they had already enabled it. Listing {@code host}
     * here rather than leaving it out is what makes that true: a default of {@code none,bridge} alone would take host
     * networking away from every deployment that had turned the flag on and never heard of this key. An operator who
     * wants a container that reaches nothing writes {@code allowed-network-modes: none}.
     */
    static final List<String> DEFAULT_ALLOWED_NETWORK_MODES = List.of("none", "bridge", "host");

    /**
     * The CPU allowance a container gets when neither the workflow nor the operator names one.
     *
     * <p>
     * One whole CPU: the interpreters this runner starts are single-threaded by default, so a script that does its work
     * rather than spinning sees no difference, and a script that spins takes one core instead of every core the host
     * has.
     */
    static final String DEFAULT_CPU = "1";

    /**
     * The largest CPU allowance a workflow may ask for when the operator has named none. Four CPUs leaves genuine
     * parallel work room to ask for it while keeping one task off a whole large host.
     */
    static final String DEFAULT_MAX_CPU = "4";

    /**
     * The memory allowance a container gets when neither the workflow nor the operator names one. 512 MiB runs a Python
     * or Node interpreter and the ordinary data munging these actions exist for; without it the container's ceiling is
     * the host's RAM, and the kernel's OOM killer chooses its victim across the whole host rather than inside the
     * container.
     */
    static final String DEFAULT_MEMORY = "512m";

    /**
     * The largest memory allowance a workflow may ask for when the operator has named none.
     */
    static final String DEFAULT_MAX_MEMORY = "2g";

    /**
     * The number of processes one container may hold open when the operator has named none.
     *
     * <p>
     * 512 is far above what any script this runner is meant to run needs - an interpreter, a shell pipeline, a handful
     * of workers - and far below what it takes to exhaust a host's PID space. Without it, {@code :(){ :|:& };:} in a
     * {@code shell} action forks until the host cannot fork, which reaches the server's own JVM; the execution timeout
     * is no defence, because the damage is done long before it fires.
     */
    static final String DEFAULT_PIDS_LIMIT = "512";

    private static final String RUNNER_TYPE = "docker";
    private static final String HOST_PROPERTY = "host";
    private static final String ALLOW_VOLUME_MOUNTS_PROPERTY = "allow-volume-mounts";

    private final String host;
    private final List<String> allowedImages;
    private final List<String> allowedNetworkModes;
    private final boolean hostNetworkAllowed;
    private final boolean volumeMountAllowed;
    private final String defaultCpu;
    private final String maxCpu;
    private final String defaultMemory;
    private final String maxMemory;
    private final String pidsLimit;

    private DockerOperatorSettings(
        String host, List<String> allowedImages, List<String> allowedNetworkModes, boolean hostNetworkAllowed,
        boolean volumeMountAllowed, String defaultCpu, String maxCpu, String defaultMemory, String maxMemory,
        String pidsLimit) {

        this.host = host;
        this.allowedImages = allowedImages;
        this.allowedNetworkModes = allowedNetworkModes;
        this.hostNetworkAllowed = hostNetworkAllowed;
        this.volumeMountAllowed = volumeMountAllowed;
        this.defaultCpu = defaultCpu;
        this.maxCpu = maxCpu;
        this.defaultMemory = defaultMemory;
        this.maxMemory = maxMemory;
        this.pidsLimit = pidsLimit;
    }

    public static DockerOperatorSettings of(ApplicationProperties applicationProperties) {
        Map<String, String> properties = propertiesOf(applicationProperties);

        return new DockerOperatorSettings(
            properties.getOrDefault(HOST_PROPERTY, ""),
            parseList(properties.get(ALLOWED_IMAGES_PROPERTY), List.of()),
            parseList(properties.get(ALLOWED_NETWORK_MODES_PROPERTY), DEFAULT_ALLOWED_NETWORK_MODES),
            TaskRunnerOperatorFlag.isEnabled(applicationProperties, RUNNER_TYPE, ALLOW_HOST_NETWORK_PROPERTY),
            TaskRunnerOperatorFlag.isEnabled(applicationProperties, RUNNER_TYPE, ALLOW_VOLUME_MOUNTS_PROPERTY),
            valueOrDefault(properties.get(DEFAULT_CPU_PROPERTY), DEFAULT_CPU),
            valueOrDefault(properties.get(MAX_CPU_PROPERTY), DEFAULT_MAX_CPU),
            valueOrDefault(properties.get(DEFAULT_MEMORY_PROPERTY), DEFAULT_MEMORY),
            valueOrDefault(properties.get(MAX_MEMORY_PROPERTY), DEFAULT_MAX_MEMORY),
            valueOrDefault(properties.get(PIDS_LIMIT_PROPERTY), DEFAULT_PIDS_LIMIT));
    }

    public List<String> getAllowedImages() {
        return List.copyOf(allowedImages);
    }

    /**
     * The network modes a workflow may ask for, as the operator named them, or {@link #DEFAULT_ALLOWED_NETWORK_MODES}
     * when they named none. {@code host} still needs {@link #ALLOW_HOST_NETWORK_PROPERTY} on top of appearing here.
     */
    public List<String> getAllowedNetworkModes() {
        return List.copyOf(allowedNetworkModes);
    }

    /**
     * The CPU allowance for a workflow that asks for none, as the operator wrote it.
     */
    public String getDefaultCpu() {
        return defaultCpu;
    }

    /**
     * The memory allowance for a workflow that asks for none, as the operator wrote it.
     */
    public String getDefaultMemory() {
        return defaultMemory;
    }

    public String getHost() {
        return host;
    }

    /**
     * The largest CPU allowance a workflow may ask for, as the operator wrote it.
     */
    public String getMaxCpu() {
        return maxCpu;
    }

    /**
     * The largest memory allowance a workflow may ask for, as the operator wrote it.
     */
    public String getMaxMemory() {
        return maxMemory;
    }

    /**
     * The number of processes one container may hold open, as the operator wrote it.
     */
    public String getPidsLimit() {
        return pidsLimit;
    }

    /**
     * Whether the given network mode may be asked for, by exact string equality against the allowlist.
     */
    public boolean isNetworkModeAllowed(String networkMode) {
        return allowedNetworkModes.contains(networkMode);
    }

    public boolean isHostNetworkAllowed() {
        return hostNetworkAllowed;
    }

    /**
     * Whether the given image reference may be run, by exact string equality against the allowlist. An empty allowlist
     * permits nothing.
     */
    public boolean isImageAllowed(String image) {
        return allowedImages.contains(image);
    }

    public boolean isVolumeMountAllowed() {
        return volumeMountAllowed;
    }

    /**
     * One comma-separated operator string as a trimmed, blank-filtered list, or the given fallback when the key is
     * absent or blank. The fallback is what separates the two lists: an absent image allowlist means nothing runs, an
     * absent network-mode allowlist means the modes the runner has always accepted.
     */
    private static List<String> parseList(String value, List<String> defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .toList();
    }

    private static String valueOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value.trim();
    }

    private static Map<String, String> propertiesOf(ApplicationProperties applicationProperties) {
        ApplicationProperties.Script script = applicationProperties.getScript();

        // Both maps bind null when their YAML key is written with an empty body, matching the fail-closed reads in
        // TaskRunnerOperatorFlag - a half-written configuration falls back to a closed runner rather than throwing.
        Map<String, ApplicationProperties.Script.Runner> runners = script.getRunners();

        if (runners == null) {
            return Map.of();
        }

        ApplicationProperties.Script.Runner runner = runners.get(RUNNER_TYPE);

        if (runner == null) {
            return Map.of();
        }

        Map<String, String> properties = runner.getProperties();

        if (properties == null) {
            return Map.of();
        }

        return properties;
    }
}
