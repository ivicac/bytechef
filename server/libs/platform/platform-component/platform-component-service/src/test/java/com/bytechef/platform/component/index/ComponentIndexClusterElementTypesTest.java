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
import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.platform.component.definition.ClusterRootComponentDefinition;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The editor's Voice Agent picker filters the index-backed component list by each component's cluster element types, so
 * a provider that is not a cluster root itself must still carry its elements' types through the index.
 *
 * @author Ivica Cardic
 */
class ComponentIndexClusterElementTypesTest {

    @Test
    void testNonRootComponentKeepsItsClusterElementTypesThroughTheIndexRoundTrip() {
        ComponentDefinition definition = component("providerLike")
            .clusterElements(
                ComponentDsl.<VoiceAgentFunction>clusterElement("voiceAgent")
                    .title("Voice Agent")
                    .type(VoiceAgentFunction.VOICE_AGENT));

        ComponentIndex.Entry entry = ComponentIndexGenerator.toEntry(definition, "com.example.Handler", "default");

        ComponentDefinition stub = ComponentIndex.toStubComponentDefinition(entry);

        assertThat(stub).isNotInstanceOf(ClusterRootComponentDefinition.class);

        List<ClusterElementDefinition<?>> clusterElements = stub.getClusterElements();

        assertThat(clusterElements)
            .singleElement()
            .satisfies(clusterElement -> {
                assertThat(clusterElement.getName()).isEqualTo("voiceAgent");
                assertThat(clusterElement.getType()
                    .name()).isEqualTo("VOICE_AGENT");
            });
    }
}
