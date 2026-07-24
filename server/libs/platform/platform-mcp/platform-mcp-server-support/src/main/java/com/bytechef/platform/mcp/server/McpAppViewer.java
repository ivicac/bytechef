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

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Serves a read-only MCP App viewer (data table, code workflow, custom component, file) as a static, tenant-independent
 * {@code ui://bytechef/<name>} resource. Each widget is a single self-contained HTML file (built from
 * {@code mcp-apps/<name>} and bundled onto this module's classpath as {@code mcp-apps/<name>.html}) that MCP Apps hosts
 * render in a sandboxed iframe; it is a pure projection of the backing tool's {@code structuredContent}.
 *
 * <p>
 * Unlike {@link McpAppWorkflowEditor}, these viewers render rows / source / text — no component icons — so they need no
 * public-URL injection and no CSP. When the bundled HTML is absent (server built without Node.js), the resource is
 * simply not served and the server starts normally.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class McpAppViewer {

    // MCP Apps UI resources are identified by this profile mime type (SEP-1865 / @modelcontextprotocol/ext-apps).
    public static final String RESOURCE_MIME_TYPE = "text/html;profile=mcp-app";

    private static final Logger log = LoggerFactory.getLogger(McpAppViewer.class);

    private McpAppViewer() {
    }

    /**
     * @param resourceUri the {@code ui://bytechef/<name>} identifier; its last segment names the classpath bundle
     *                    ({@code mcp-apps/<name>.html})
     */
    public static List<McpServerFeatures.AsyncResourceSpecification> getResourceSpecifications(
        String resourceUri, String title, String description) {

        String name = resourceUri.substring(resourceUri.lastIndexOf('/') + 1);
        String classpathLocation = "mcp-apps/" + name + ".html";

        ClassLoader classLoader = McpAppViewer.class.getClassLoader();

        if (classLoader.getResource(classpathLocation) == null) {
            log.warn(
                "MCP App viewer HTML not found at classpath:{}; the {} resource will not be served. Build it via its "
                    + "Gradle task (npm run build in mcp-apps/{}), then rebuild this module.",
                classpathLocation, resourceUri, name);

            return List.of();
        }

        String html;

        try (InputStream inputStream = classLoader.getResourceAsStream(classpathLocation)) {
            html = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }

        McpSchema.Resource resource = McpSchema.Resource.builder(resourceUri, title)
            .description(description)
            .mimeType(RESOURCE_MIME_TYPE)
            .build();

        McpSchema.ReadResourceResult readResourceResult = McpSchema.ReadResourceResult.builder(
            List.of(
                McpSchema.TextResourceContents.builder(resourceUri, html)
                    .mimeType(RESOURCE_MIME_TYPE)
                    .build()))
            .build();

        return List.of(new McpServerFeatures.AsyncResourceSpecification(
            resource, (exchange, readResourceRequest) -> Mono.just(readResourceResult)));
    }
}
