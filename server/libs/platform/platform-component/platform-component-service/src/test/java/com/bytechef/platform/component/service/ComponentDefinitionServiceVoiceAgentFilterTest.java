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

package com.bytechef.platform.component.service;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.clusterElement;
import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ai.agent.BaseToolFunction.TOOLS;

import com.bytechef.component.ComponentHandler;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.ComponentDefinitionRegistry;
import com.bytechef.platform.component.context.ContextFactory;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.filter.ComponentDefinitionFilter;
import com.bytechef.platform.constant.PlatformType;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Proves that the {@code deepgram}, {@code elevenLabs} and {@code openAi} components are discoverable when the editor
 * asks for components offering a {@code VOICE_AGENT} cluster element, for the {@code browser/v1/voiceSession} trigger's
 * {@code VOICE_AGENT} slot. None of the three is a cluster root itself (only the {@code browser} trigger is); each
 * merely declares a {@code voiceAgent} cluster element of type {@link VoiceAgentFunction#VOICE_AGENT}, the same way
 * {@code DeepgramVoiceAgent}, {@code ElevenLabsVoiceAgent} and {@code OpenAiVoiceAgent} do in their real modules.
 *
 * <p>
 * The client never sends a cluster element type to the server: {@code useWorkflowLayout.ts} fetches every component
 * definition with {@code clusterElementDefinitions: true} and {@code WorkflowNodesPopoverMenuComponentList.tsx} filters
 * the result client-side by {@code component.clusterElements[*].type.name}. So the server-side contract this test
 * protects is narrower than a type filter: {@link ComponentDefinitionServiceImpl#getComponentDefinitions} must include
 * every component that provides a cluster element (not only cluster roots), and must carry each element's declared
 * {@code type} through to the returned {@link ComponentDefinition#getClusterElements()} untouched.
 * </p>
 *
 * <p>
 * This test builds minimal in-test component definitions instead of depending on the real {@code deepgram},
 * {@code elevenlabs} and {@code open-ai} (ai/llm) modules: {@code open-ai} pulls
 * {@code com.openai:openai-java-client-okhttp} and {@code org.springframework.ai:spring-ai-openai} onto its classpath,
 * and {@code deepgram}/{@code elevenlabs} pull the {@code ai/llm} module (which brings in
 * {@code spring-ai-client-chat}) plus {@code voice-test-support} — none of which belong on
 * {@code platform-component-service}'s test classpath, a foundational module the component modules themselves depend
 * on.
 * </p>
 *
 * @author Ivica Cardic
 */
public class ComponentDefinitionServiceVoiceAgentFilterTest {

    private static final ComponentHandler DEEPGRAM_HANDLER = () -> component("deepgram")
        .title("Deepgram")
        .clusterElements(voiceAgentElement("Deepgram Voice Agent"));

    private static final ComponentHandler ELEVEN_LABS_HANDLER = () -> component("elevenLabs")
        .title("ElevenLabs")
        .clusterElements(voiceAgentElement("ElevenLabs Voice Agent"));

    private static final ComponentHandler OPEN_AI_HANDLER = () -> component("openAi")
        .title("OpenAI")
        .clusterElements(voiceAgentElement("OpenAI Voice Agent"));

    private static final ComponentHandler TOOLS_ONLY_HANDLER = () -> component("toolsOnlyComponent")
        .title("Tools Only")
        .clusterElements(
            clusterElement("someTool")
                .type(TOOLS)
                .title("Some Tool"));

    private static final ComponentHandler PLAIN_HANDLER = () -> component("plainComponent")
        .title("Plain")
        .actions(action("get").title("Get"));

    @Test
    public void testGetComponentDefinitionsListsVoiceAgentProvidersForTheTriggersSlot() {
        ComponentDefinitionService componentDefinitionService = createComponentDefinitionService();

        List<ComponentDefinition> componentDefinitions = componentDefinitionService.getComponentDefinitions(
            null, true, null, null, null, PlatformType.AUTOMATION);

        List<String> voiceAgentProviderNames = componentDefinitions.stream()
            .filter(componentDefinition -> componentDefinition.getClusterElements()
                .stream()
                .anyMatch(
                    clusterElementDefinition -> clusterElementDefinition.getType() == VoiceAgentFunction.VOICE_AGENT))
            .map(ComponentDefinition::getName)
            .toList();

        Assertions.assertThat(voiceAgentProviderNames)
            .containsExactlyInAnyOrder("deepgram", "elevenLabs", "openAi");
    }

    private static com.bytechef.component.definition.ClusterElementDefinition<VoiceAgentFunction> voiceAgentElement(
        String title) {

        return com.bytechef.component.definition.ComponentDsl.<VoiceAgentFunction>clusterElement("voiceAgent")
            .type(VoiceAgentFunction.VOICE_AGENT)
            .title(title);
    }

    private static ComponentDefinitionService createComponentDefinitionService() {
        ApplicationProperties applicationProperties = new ApplicationProperties();
        ApplicationProperties.Component component = new ApplicationProperties.Component();

        component.setRegistry(new ApplicationProperties.Component.Registry());
        applicationProperties.setComponent(component);

        ComponentDefinitionRegistry componentDefinitionRegistry = new ComponentDefinitionRegistry(
            applicationProperties,
            List.of(DEEPGRAM_HANDLER, ELEVEN_LABS_HANDLER, OPEN_AI_HANDLER, TOOLS_ONLY_HANDLER, PLAIN_HANDLER),
            List::of, List.of());

        return new ComponentDefinitionServiceImpl(
            List.of(new AllComponentDefinitionFilter()), componentDefinitionRegistry,
            Mockito.mock(ContextFactory.class), List.of());
    }

    private static class AllComponentDefinitionFilter implements ComponentDefinitionFilter {

        /**
         * Passes every component through: this test is about which components carry a VOICE_AGENT cluster element, so
         * the filter must not be what decides which components are visible.
         */
        @Override
        public boolean filter(ComponentDefinition componentDefinition) {
            return true;
        }

        @Override
        public boolean supports(PlatformType type) {
            return true;
        }
    }
}
