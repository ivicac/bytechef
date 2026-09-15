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

package com.bytechef.platform.component.index;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.trigger;
import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.ClusterElementDefinition.ClusterElementType;
import com.bytechef.component.definition.ComponentDsl.ModifiableComponentDefinition;
import com.bytechef.component.definition.TriggerDefinition.TriggerType;
import com.bytechef.component.definition.ai.agent.BaseToolFunction;
import com.bytechef.platform.component.definition.AbstractComponentDefinitionWrapper;
import com.bytechef.platform.component.definition.ClusterRootComponentDefinition;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import com.bytechef.platform.component.domain.ComponentDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class ComponentIndexTriggerClusterRootTest {

    @Test
    void testTriggerOnlyClusterRootSurvivesTheIndexRoundTrip() {
        TriggerClusterRoot definition = new TriggerClusterRoot(
            component("browserLike")
                .triggers(trigger("voiceSession").type(TriggerType.WEBSOCKET)));

        ComponentIndex.Entry entry = ComponentIndexGenerator.toEntry(definition, "com.example.Handler", "default");

        assertThat(ComponentIndex.toStubComponentDefinition(entry))
            .isInstanceOfSatisfying(ClusterRootComponentDefinition.class, stub -> {
                assertThat(new ComponentDefinition(stub).isClusterRoot()).isTrue();
                assertThat(stub.getClusterElementTypes())
                    .extracting(ClusterElementType::name)
                    .containsExactly("VOICE_AGENT", "TOOLS");
            });
    }

    private static class TriggerClusterRoot extends AbstractComponentDefinitionWrapper
        implements ClusterRootComponentDefinition {

        private TriggerClusterRoot(ModifiableComponentDefinition componentDefinition) {
            super(componentDefinition);
        }

        @Override
        public List<ClusterElementType> getClusterElementTypes() {
            return List.of(VoiceAgentFunction.VOICE_AGENT, BaseToolFunction.TOOLS);
        }
    }
}
