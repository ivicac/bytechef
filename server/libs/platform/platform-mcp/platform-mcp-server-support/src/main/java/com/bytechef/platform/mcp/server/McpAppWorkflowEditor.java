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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

/**
 * The MCP App workflow editor: a single self-contained HTML file (built from {@code mcp-apps/workflow-editor} and
 * bundled into this module's resources by the {@code buildWorkflowEditor} Gradle task) that MCP Apps hosts render in a
 * sandboxed iframe. The widget is a pure projection of workflow tool results — it reads the nested workflow definition
 * from {@code structuredContent.definition} and re-renders on every pushed result.
 *
 * <p>
 * The resource is static and tenant-independent, so it is served to every session of every ByteChef MCP server that
 * registers it. When the bundled HTML is absent (server built without Node.js), servers start normally and simply serve
 * no UI resource.
 *
 * @author Ivica Cardic
 */
public final class McpAppWorkflowEditor {

    public static final String RESOURCE_URI = "ui://bytechef/workflow-editor";

    // MCP Apps UI resources are identified by this profile mime type (SEP-1865 / @modelcontextprotocol/ext-apps
    // RESOURCE_MIME_TYPE); hosts that don't support MCP Apps ignore the resource.
    public static final String RESOURCE_MIME_TYPE = "text/html;profile=mcp-app";

    // The widget's build output (mcp-apps/workflow-editor/dist/index.html) is bundled onto the classpath renamed to
    // mcp-apps/workflow-editor.html by this module's processResources task; a matching test fixture lives at the same
    // path. Each MCP App bundle gets its own mcp-apps/<app>.html slot. Keep this in sync with the rename in
    // build.gradle.kts.
    private static final String CLASSPATH_LOCATION = "mcp-apps/workflow-editor.html";

    private static final Logger log = LoggerFactory.getLogger(McpAppWorkflowEditor.class);

    private McpAppWorkflowEditor() {
    }

    /**
     * Tool {@code _meta} marking a tool as backed by the workflow editor (modern nested {@code ui.resourceUri} format
     * per the MCP Apps extension). Hosts that see this metadata fetch and render the {@link #RESOURCE_URI} resource
     * alongside the tool result.
     */
    public static Map<String, Object> getToolMeta() {
        return Map.of("ui", Map.of("resourceUri", RESOURCE_URI));
    }

    /**
     * @param publicUrl the deployment's public base URL ({@code bytechef.public-url}); when present, the widget is
     *                  pointed at this deployment's anonymous component-icon endpoint and the resource CSP allows that
     *                  origin for static resources (maps to {@code img-src}). When absent, the widget stays fully
     *                  offline and renders fallback glyphs instead of component icons.
     */
    public static List<McpServerFeatures.AsyncResourceSpecification> getResourceSpecifications(
        @Nullable String publicUrl) {

        ClassLoader classLoader = McpAppWorkflowEditor.class.getClassLoader();

        if (classLoader.getResource(CLASSPATH_LOCATION) == null) {
            log.warn(
                "MCP App workflow editor HTML not found at classpath:{}; the {} resource will not be served. Build "
                    + "it via the buildWorkflowEditor Gradle task (npm run build in mcp-apps/workflow-editor), then "
                    + "rebuild this module.",
                CLASSPATH_LOCATION, RESOURCE_URI);

            return List.of();
        }

        String html;

        try (InputStream inputStream = classLoader.getResourceAsStream(CLASSPATH_LOCATION)) {
            html = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }

        McpSchema.Resource.Builder resourceBuilder =
            McpSchema.Resource.builder(RESOURCE_URI, "ByteChef Workflow Editor")
                .description("Read-only workflow canvas rendered while workflows are built via MCP tools")
                .mimeType(RESOURCE_MIME_TYPE);

        if (publicUrl != null && !publicUrl.isBlank()) {
            String normalizedPublicUrl = publicUrl.strip()
                .replaceAll("/+$", "");

            // The single-file bundle cannot know the deployment URL at build time; inject the icon base for
            // ComponentImage (window.__BYTECHEF_ICON_BASE_URL__) at serve time instead.
            html = html.replace(
                "<head>",
                "<head><script>window.__BYTECHEF_ICON_BASE_URL__="
                    + toJsStringLiteral(normalizedPublicUrl + "/icons/components") + ";</script>");

            resourceBuilder.meta(
                Map.of("ui", Map.of("csp", Map.of("resourceDomains", List.of(toOrigin(normalizedPublicUrl))))));
        }

        McpSchema.ReadResourceResult readResourceResult = McpSchema.ReadResourceResult.builder(
            List.of(
                McpSchema.TextResourceContents.builder(RESOURCE_URI, html)
                    .mimeType(RESOURCE_MIME_TYPE)
                    .build()))
            .build();

        return List.of(new McpServerFeatures.AsyncResourceSpecification(
            resourceBuilder.build(), (exchange, readResourceRequest) -> Mono.just(readResourceResult)));
    }

    private static String toJsStringLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("<", "\\u003c") + "\"";
    }

    private static String toOrigin(String url) {
        URI uri = URI.create(url);

        return uri.getScheme() + "://" + uri.getAuthority();
    }
}
