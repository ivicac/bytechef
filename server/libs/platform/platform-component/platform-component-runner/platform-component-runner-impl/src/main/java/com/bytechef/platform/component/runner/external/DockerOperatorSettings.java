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
 * The operator settings that gate the Docker task runner: which daemon it talks to, which images it may run, and
 * whether it may relax container isolation.
 *
 * <p>
 * {@link ApplicationProperties.Script.Runner#getProperties()} is a flat {@code Map<String, String>}, not a structured
 * type, so {@code allowed-images} cannot arrive as a list - an operator who writes a YAML list under {@code properties}
 * still gets one comma-separated string back. This class is where that string is parsed, once, into the trimmed and
 * blank-filtered list every other check compares against.
 *
 * <p>
 * The allowlist is fail-closed, and its empty state is CLOSED rather than open: an operator who enables the Docker
 * runner without naming an image gets no runnable image, not an unrestricted one. Silently reading an absent or
 * unparseable allowlist as "everything permitted" would turn one missing config line into arbitrary image execution, so
 * {@link #isImageAllowed(String)} returns {@code false} for every image until at least one is named. Matching is exact
 * string equality against that list - no prefix matching, no wildcards, no registry normalisation. An operator who
 * wants two tags lists two tags.
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

    private static final String RUNNER_TYPE = "docker";
    private static final String HOST_PROPERTY = "host";
    private static final String ALLOW_VOLUME_MOUNTS_PROPERTY = "allow-volume-mounts";

    private final String host;
    private final List<String> allowedImages;
    private final boolean hostNetworkAllowed;
    private final boolean volumeMountAllowed;

    private DockerOperatorSettings(
        String host, List<String> allowedImages, boolean hostNetworkAllowed, boolean volumeMountAllowed) {

        this.host = host;
        this.allowedImages = allowedImages;
        this.hostNetworkAllowed = hostNetworkAllowed;
        this.volumeMountAllowed = volumeMountAllowed;
    }

    public static DockerOperatorSettings of(ApplicationProperties applicationProperties) {
        Map<String, String> properties = propertiesOf(applicationProperties);

        return new DockerOperatorSettings(
            properties.getOrDefault(HOST_PROPERTY, ""),
            parseAllowedImages(properties.get(ALLOWED_IMAGES_PROPERTY)),
            TaskRunnerOperatorFlag.isEnabled(applicationProperties, RUNNER_TYPE, ALLOW_HOST_NETWORK_PROPERTY),
            TaskRunnerOperatorFlag.isEnabled(applicationProperties, RUNNER_TYPE, ALLOW_VOLUME_MOUNTS_PROPERTY));
    }

    public List<String> getAllowedImages() {
        return List.copyOf(allowedImages);
    }

    public String getHost() {
        return host;
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

    private static List<String> parseAllowedImages(String allowedImages) {
        if (allowedImages == null || allowedImages.isBlank()) {
            return List.of();
        }

        return Arrays.stream(allowedImages.split(","))
            .map(String::trim)
            .filter(image -> !image.isEmpty())
            .toList();
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
