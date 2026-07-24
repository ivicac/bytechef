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
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link McpAppViewer}. A viewer HTML bundle on the classpath (a test fixture here for
 * {@code data-table-viewer}; the built widget bundles in production) is served as its {@code ui://bytechef/<name>}
 * resource; an absent bundle yields no resource.
 *
 * @author Ivica Cardic
 */
class McpAppViewerTest {

    private static final String DATA_TABLE_VIEWER_URI = "ui://bytechef/data-table-viewer";

    @Test
    void testGetResourceSpecificationsServesBundledHtml() {
        List<McpServerFeatures.AsyncResourceSpecification> specifications = McpAppViewer.getResourceSpecifications(
            DATA_TABLE_VIEWER_URI, "Data Table Viewer", "Read-only data table");

        assertThat(specifications).hasSize(1);

        McpSchema.Resource resource = specifications.getFirst()
            .resource();

        assertThat(resource.uri()).isEqualTo(DATA_TABLE_VIEWER_URI);
        assertThat(resource.mimeType()).isEqualTo("text/html;profile=mcp-app");

        McpSchema.ReadResourceResult readResourceResult = specifications.getFirst()
            .readHandler()
            .apply(null, new McpSchema.ReadResourceRequest(DATA_TABLE_VIEWER_URI))
            .block();

        assertThat(readResourceResult).isNotNull();

        McpSchema.TextResourceContents textResourceContents = (McpSchema.TextResourceContents) readResourceResult
            .contents()
            .getFirst();

        assertThat(textResourceContents.text()).contains("Data Table Viewer Fixture");
    }

    @Test
    void testAbsentBundleReturnsNoResource() {
        assertThat(McpAppViewer.getResourceSpecifications(
            "ui://bytechef/nonexistent-viewer", "Missing Viewer", "No bundle on the classpath")).isEmpty();
    }
}
