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

import com.bytechef.config.ApplicationProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The allowlist is fail-closed in both of its failure directions, and both are asserted here: an absent or half-written
 * configuration permits nothing, and a named image matches by exact equality rather than by prefix.
 *
 * @author Ivica Cardic
 */
class DockerOperatorSettingsTest {

    @Test
    void testAbsentRunnerEntryYieldsAClosedConfiguration() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(applicationProperties(null));

        assertThat(settings.getAllowedImages()).isEmpty();
        assertThat(settings.isHostNetworkAllowed()).isFalse();
        assertThat(settings.isVolumeMountAllowed()).isFalse();
        assertThat(settings.isImageAllowed("ubuntu:24.04")).isFalse();
    }

    @Test
    void testAnEmptyAllowlistPermitsNothing() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(applicationProperties(Map.of()));

        assertThat(settings.isImageAllowed("ubuntu:24.04")).isFalse();
    }

    @Test
    void testAllowedImagesAreParsedAsACommaSeparatedList() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", " ubuntu:24.04 , python:3.12-slim ")));

        assertThat(settings.getAllowedImages()).containsExactly("ubuntu:24.04", "python:3.12-slim");
        assertThat(settings.isImageAllowed("ubuntu:24.04")).isTrue();
        assertThat(settings.isImageAllowed("python:3.12-slim")).isTrue();
    }

    @Test
    void testAnImageOutsideTheAllowlistIsRejected() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", "ubuntu:24.04")));

        assertThat(settings.isImageAllowed("alpine:latest")).isFalse();
    }

    @Test
    void testAnUntaggedImageDoesNotMatchATaggedAllowlistEntry() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", "ubuntu:24.04")));

        assertThat(settings.isImageAllowed("ubuntu")).isFalse();
        assertThat(settings.isImageAllowed("ubuntu:latest")).isFalse();
    }

    @Test
    void testAPrefixOfAnAllowedImageIsRejected() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", "ubuntu:24.04")));

        assertThat(settings.isImageAllowed("ubuntu:24.04-evil")).isFalse();
        assertThat(settings.isImageAllowed("evil/ubuntu:24.04")).isFalse();
    }

    @Test
    void testBlankEntriesAreDropped() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allowed-images", "ubuntu:24.04, ,,python:3.12-slim")));

        assertThat(settings.getAllowedImages()).containsExactly("ubuntu:24.04", "python:3.12-slim");
    }

    @Test
    void testHostDefaultsToEmptyMeaningTheAmbientDaemon() {
        assertThat(DockerOperatorSettings.of(applicationProperties(Map.of()))
            .getHost()).isEmpty();
    }

    @Test
    void testMalformedBooleansFailClosed() {
        DockerOperatorSettings settings = DockerOperatorSettings.of(
            applicationProperties(Map.of("allow-host-network", "yes", "allow-volume-mounts", "")));

        assertThat(settings.isHostNetworkAllowed()).isFalse();
        assertThat(settings.isVolumeMountAllowed()).isFalse();
    }

    private static ApplicationProperties applicationProperties(Map<String, String> properties) {
        ApplicationProperties applicationProperties = new ApplicationProperties();

        ApplicationProperties.Script script = applicationProperties.getScript();

        if (properties == null) {
            script.setRunners(new HashMap<>());

            return applicationProperties;
        }

        ApplicationProperties.Script.Runner runner = new ApplicationProperties.Script.Runner();

        runner.setEnabled(true);
        runner.setProperties(new HashMap<>(properties));

        Map<String, ApplicationProperties.Script.Runner> runners = new HashMap<>();

        runners.put("docker", runner);

        script.setRunners(runners);

        return applicationProperties;
    }
}
