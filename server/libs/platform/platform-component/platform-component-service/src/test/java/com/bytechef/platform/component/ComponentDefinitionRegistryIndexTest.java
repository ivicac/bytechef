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

package com.bytechef.platform.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.slack.SlackComponentHandler;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.index.ComponentIndex;
import com.bytechef.platform.component.index.ComponentIndexGenerator;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the build-time-index path of {@link ComponentDefinitionRegistry}: the components list is served from
 * lightweight index stubs without loading handlers, while single-component access loads just the requested handler
 * (with full, executable definitions), plus the generator/loader round trip that produces the index.
 *
 * @author Ivica Cardic
 */
public class ComponentDefinitionRegistryIndexTest {

    @TempDir
    Path tempDir;

    private static ComponentDefinitionRegistry createRegistry(ComponentIndex componentIndex) {
        ApplicationProperties applicationProperties = new ApplicationProperties();
        ApplicationProperties.Component component = new ApplicationProperties.Component();

        component.setRegistry(new ApplicationProperties.Component.Registry());
        applicationProperties.setComponent(component);

        return new ComponentDefinitionRegistry(
            applicationProperties, List.of(), List::of, List.of(), () -> Optional.of(componentIndex));
    }

    private static ComponentIndex createSlackIndex() {
        SlackComponentHandler slackComponentHandler = new SlackComponentHandler();

        ComponentDefinition slackComponentDefinition = slackComponentHandler.getDefinition();

        List<ComponentIndex.ItemSummary> actionSummaries = slackComponentDefinition.getActions()
            .orElse(List.of())
            .stream()
            .map(actionDefinition -> new ComponentIndex.ItemSummary(
                actionDefinition.getName(), actionDefinition.getTitle()
                    .orElse(null),
                actionDefinition.getDescription()
                    .orElse(null)))
            .toList();

        return new ComponentIndex(
            List.of(
                new ComponentIndex.Entry(
                    "slack", slackComponentDefinition.getVersion(), "Slack",
                    "Slack is a messaging platform for teams to communicate and collaborate.",
                    "path:assets/slack.svg", null, null,
                    new ComponentIndex.ConnectionSummary(1, true), actionSummaries, null, null,
                    List.of("channel"), SlackComponentHandler.class.getName(), "default")));
    }

    @Test
    public void testGetStaticComponentDefinitionsServesStubsFromIndex() {
        ComponentDefinitionRegistry componentDefinitionRegistry = createRegistry(createSlackIndex());

        List<ComponentDefinition> componentDefinitions = componentDefinitionRegistry.getStaticComponentDefinitions();

        assertThat(componentDefinitions)
            .extracting(ComponentDefinition::getName)
            .contains("slack", "manual", "missing");

        ComponentDefinition slackStub = componentDefinitions.stream()
            .filter(componentDefinition -> "slack".equals(componentDefinition.getName()))
            .findFirst()
            .orElseThrow();

        // The stub carries list-view metadata (identity, action summaries, connection presence)...
        assertThat(slackStub.getTitle()).contains("Slack");
        assertThat(slackStub.getActions()
            .orElse(List.of())).isNotEmpty();
        assertThat(slackStub.getConnection()).isPresent();

        // ...but no property trees — the stub never loaded the real handler.
        assertThat(slackStub.getActions()
            .orElseThrow()
            .getFirst()
            .getProperties()
            .orElse(List.of())).isEmpty();
    }

    @Test
    public void testGetComponentDefinitionLoadsFullComponentOnDemand() {
        ComponentDefinitionRegistry componentDefinitionRegistry = createRegistry(createSlackIndex());

        ComponentDefinition componentDefinition = componentDefinitionRegistry.getComponentDefinition("slack", 1);

        // The on-demand load returns the real definition: actions carry their full property trees.
        assertThat(componentDefinition.getActions()
            .orElseThrow()
            .stream()
            .anyMatch(actionDefinition -> !actionDefinition.getProperties()
                .orElse(List.of())
                .isEmpty()))
                    .isTrue();

        assertThat(componentDefinition.getConnection()
            .orElseThrow()
            .getAuthorizations()
            .orElse(List.of())).isNotEmpty();
    }

    @Test
    public void testGetComponentDefinitionsForUnknownNameReturnsEmpty() {
        ComponentDefinitionRegistry componentDefinitionRegistry = createRegistry(createSlackIndex());

        assertThat(componentDefinitionRegistry.getComponentDefinitions("nonexistent")).isEmpty();
    }

    @Test
    public void testGeneratorRoundTrip() throws Exception {
        Path indexPath = tempDir.resolve("META-INF/bytechef/component-index.json");

        ComponentIndexGenerator.main(new String[] {
            indexPath.toString()
        });

        try (URLClassLoader classLoader = new URLClassLoader(
            new URL[] {
                tempDir.toUri()
                    .toURL()
            }, null)) {

            Optional<ComponentIndex> componentIndexOptional = ComponentIndex.load(classLoader);

            assertThat(componentIndexOptional).isPresent();

            ComponentIndex componentIndex = componentIndexOptional.orElseThrow();

            ComponentIndex.Entry slackEntry = componentIndex.entries()
                .stream()
                .filter(entry -> "slack".equals(entry.name()))
                .findFirst()
                .orElseThrow();

            assertThat(slackEntry.providerClassName()).isEqualTo(SlackComponentHandler.class.getName());
            assertThat(slackEntry.loaderKind()).isEqualTo("default");
            assertThat(slackEntry.actions()).isNotEmpty();
            assertThat(slackEntry.connection()).isNotNull();
        }

        assertThat(Files.readString(indexPath)).contains("\"slack\"");
    }
}
