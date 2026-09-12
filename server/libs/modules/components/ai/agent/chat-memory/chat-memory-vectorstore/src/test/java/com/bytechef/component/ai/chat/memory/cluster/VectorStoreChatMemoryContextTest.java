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

package com.bytechef.component.ai.chat.memory.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ai.agent.ChatMemoryFunction;
import com.bytechef.platform.component.definition.ai.agent.VectorStoreFunction;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Coverage for the second half of the chat-memory path: the context the agent action now hands to the five-argument
 * {@link ChatMemoryFunction#apply} must reach the {@link VectorStoreFunction} this element wraps, because that is where
 * a store whose identity belongs to an account resolves its owner.
 *
 * <p>
 * Both assertions here are controls rather than restatements. The element is published as {@code () -> this}, not as a
 * {@code this::apply} method reference: a method reference implements only the SAM, so the interface default would run
 * for a five-argument call and delegate back to the context-free form, and the recorded context would be null.
 * </p>
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class VectorStoreChatMemoryContextTest {

    @Mock
    private ClusterElementDefinitionService clusterElementDefinitionService;

    @Test
    void testTheContextReachesTheVectorStoreFunction() throws Exception {
        RecordingVectorStoreFunction vectorStoreFunction = new RecordingVectorStoreFunction();

        when(clusterElementDefinitionService.<VectorStoreFunction>getClusterElement(
            eq("testVectorStore"), eq(1), eq("testVectorStoreElement"))).thenReturn(vectorStoreFunction);

        Context context = mock(Context.class);

        ChatMemoryFunction chatMemoryFunction = getChatMemoryFunction();

        chatMemoryFunction.apply(
            MockParametersFactory.create(Map.of()), MockParametersFactory.create(Map.of()), buildExtensions(),
            buildComponentConnections(), context);

        assertThat(vectorStoreFunction.receivedContext).isSameAs(context);
    }

    @Test
    void testTheContextFreeFormStillBuildsAnAdvisor() throws Exception {
        RecordingVectorStoreFunction vectorStoreFunction = new RecordingVectorStoreFunction();

        when(clusterElementDefinitionService.<VectorStoreFunction>getClusterElement(
            eq("testVectorStore"), eq(1), eq("testVectorStoreElement"))).thenReturn(vectorStoreFunction);

        ChatMemoryFunction chatMemoryFunction = getChatMemoryFunction();

        // A caller holding no context of its own still works; the absence is passed on rather than papered over, so
        // the frame that resolves an owner can see it.
        ChatMemoryFunction.Result result = chatMemoryFunction.apply(
            MockParametersFactory.create(Map.of()), MockParametersFactory.create(Map.of()), buildExtensions(),
            buildComponentConnections());

        assertThat(result.advisor()).isNotNull();
        assertThat(vectorStoreFunction.receivedContext).isNull();
    }

    private ChatMemoryFunction getChatMemoryFunction() {
        ClusterElementDefinition<ChatMemoryFunction> clusterElementDefinition =
            VectorStoreChatMemory.of(clusterElementDefinitionService);

        return clusterElementDefinition.getElement();
    }

    private static Parameters buildExtensions() {
        Map<String, Object> vectorStoreElement = new HashMap<>();

        vectorStoreElement.put("name", "vectorStore_1");
        vectorStoreElement.put("type", "testVectorStore/v1/testVectorStoreElement");
        vectorStoreElement.put("parameters", new HashMap<String, Object>());

        return MockParametersFactory.create(Map.of("clusterElements", Map.of("vectorStore", vectorStoreElement)));
    }

    private static Map<String, ComponentConnection> buildComponentConnections() {
        return Map.of("vectorStore_1", new ComponentConnection("testVectorStore", 1, 1L, Map.of(), null));
    }

    private static final class RecordingVectorStoreFunction implements VectorStoreFunction {

        private @Nullable Context receivedContext;

        @Override
        public VectorStore apply(
            Parameters inputParameters, Parameters connectionParameters, Parameters extensions,
            Map<String, ComponentConnection> componentConnections, Context context) {

            receivedContext = context;

            return mock(VectorStore.class);
        }
    }
}
