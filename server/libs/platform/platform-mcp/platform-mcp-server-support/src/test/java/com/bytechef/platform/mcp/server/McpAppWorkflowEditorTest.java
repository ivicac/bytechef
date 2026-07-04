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

package com.bytechef.platform.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link McpAppWorkflowEditor}. The workflow editor HTML on the classpath (a test fixture here; the built
 * widget bundle in production) is served as the {@code ui://bytechef/workflow-editor} resource.
 *
 * @author Ivica Cardic
 */
class McpAppWorkflowEditorTest {

    @Test
    void testGetResourceSpecificationsServesBundledHtml() {
        List<McpServerFeatures.AsyncResourceSpecification> specifications =
            McpAppWorkflowEditor.getResourceSpecifications(null);

        assertThat(specifications).hasSize(1);

        McpSchema.Resource resource = specifications.getFirst()
            .resource();

        assertThat(resource.uri()).isEqualTo(McpAppWorkflowEditor.RESOURCE_URI);
        assertThat(resource.mimeType()).isEqualTo("text/html;profile=mcp-app");

        McpSchema.ReadResourceResult readResourceResult = specifications.getFirst()
            .readHandler()
            .apply(null, new McpSchema.ReadResourceRequest(McpAppWorkflowEditor.RESOURCE_URI))
            .block();

        assertThat(readResourceResult).isNotNull();
        assertThat(readResourceResult.contents()).hasSize(1);

        McpSchema.TextResourceContents textResourceContents = (McpSchema.TextResourceContents) readResourceResult
            .contents()
            .getFirst();

        assertThat(textResourceContents.uri()).isEqualTo(McpAppWorkflowEditor.RESOURCE_URI);
        assertThat(textResourceContents.mimeType()).isEqualTo("text/html;profile=mcp-app");
        assertThat(textResourceContents.text()).contains("<!doctype html>");
    }

    @Test
    void testGetResourceSpecificationsInjectsIconBaseUrlAndCspForPublicUrl() {
        List<McpServerFeatures.AsyncResourceSpecification> specifications =
            McpAppWorkflowEditor.getResourceSpecifications("https://bytechef.example.com:8443/");

        assertThat(specifications).hasSize(1);

        McpSchema.Resource resource = specifications.getFirst()
            .resource();

        assertThat(resource.meta()).containsEntry(
            "ui",
            Map.of("csp", Map.of("resourceDomains", List.of("https://bytechef.example.com:8443"))));

        McpSchema.ReadResourceResult readResourceResult = specifications.getFirst()
            .readHandler()
            .apply(null, new McpSchema.ReadResourceRequest(McpAppWorkflowEditor.RESOURCE_URI))
            .block();

        assertThat(readResourceResult).isNotNull();

        McpSchema.TextResourceContents textResourceContents = (McpSchema.TextResourceContents) readResourceResult
            .contents()
            .getFirst();

        assertThat(textResourceContents.text()).contains(
            "window.__BYTECHEF_ICON_BASE_URL__=\"https://bytechef.example.com:8443/icons/components\";");
    }

    @Test
    void testGetToolMetaUsesModernNestedFormat() {
        Map<String, Object> toolMeta = McpAppWorkflowEditor.getToolMeta();

        assertThat(toolMeta).containsEntry("ui", Map.of("resourceUri", McpAppWorkflowEditor.RESOURCE_URI));
    }
}
