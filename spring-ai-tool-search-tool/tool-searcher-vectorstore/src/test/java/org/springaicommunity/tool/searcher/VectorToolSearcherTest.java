/*
* Copyright 2025 - 2025 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.springaicommunity.tool.searcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.tool.search.ToolSearchRequest;
import org.springaicommunity.tool.search.ToolSearchResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

@ExtendWith(MockitoExtension.class)
class VectorToolSearcherTest {

    @Mock
    private VectorStore vectorStore;

    private static Document doc(String id, String sessionId, String toolName) {
        return new Document(
            id, toolName + " description",
            Map.of("sessionId", sessionId, "id", id, "toolName", toolName, "toolDescription", toolName + " description"));
    }

    @Test
    void testSearchReturnsRequestSessionAndAdditionalSessions() {
        VectorToolSearcher searcher = new VectorToolSearcher(vectorStore, Set.of("ai_hub_tool_catalog"));

        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.<org.springframework.ai.vectorstore.SearchRequest>any()))
            .thenReturn(List.of(
                doc("1", "thread-123", "listTasks"),
                doc("2", "ai_hub_tool_catalog", "slack_sendMessage"),
                doc("3", "some-other-session", "ignoreMe")));

        ToolSearchResponse response = searcher.search(new ToolSearchRequest("thread-123", "send a message", 5, null));

        assertThat(response.toolReferences())
            .extracting(tr -> tr.toolName())
            .containsExactlyInAnyOrder("listTasks", "slack_sendMessage");
    }

    @Test
    void testDefaultConstructorMatchesOnlyRequestSession() {
        VectorToolSearcher searcher = new VectorToolSearcher(vectorStore);

        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.<org.springframework.ai.vectorstore.SearchRequest>any()))
            .thenReturn(List.of(doc("1", "thread-123", "listTasks"), doc("2", "ai_hub_tool_catalog", "slack_sendMessage")));

        ToolSearchResponse response = searcher.search(new ToolSearchRequest("thread-123", "q", 5, null));

        assertThat(response.toolReferences()).extracting(tr -> tr.toolName()).containsExactly("listTasks");
    }

    @Test
    void testClearSessionDeletesByMetadataFilter() {
        VectorToolSearcher searcher = new VectorToolSearcher(vectorStore, Set.of("ai_hub_tool_catalog"));

        searcher.clearSession("ai_hub_tool_catalog");

        verify(vectorStore).delete(any(Filter.Expression.class));
    }

    @Test
    void testSearchSkipsDocumentWithNullSessionIdWithoutNpe() {
        VectorToolSearcher searcher = new VectorToolSearcher(vectorStore, Set.of("ai_hub_tool_catalog"));

        Document ghost = new Document(
            "9", "ghost description", Map.of("id", "9", "toolName", "ghost", "toolDescription", "ghost description"));

        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.<org.springframework.ai.vectorstore.SearchRequest>any()))
            .thenReturn(List.of(ghost, doc("2", "ai_hub_tool_catalog", "slack_sendMessage")));

        ToolSearchResponse response = searcher.search(new ToolSearchRequest("thread-123", "q", 5, null));

        assertThat(response.toolReferences()).extracting(tr -> tr.toolName()).containsExactly("slack_sendMessage");
    }
}
