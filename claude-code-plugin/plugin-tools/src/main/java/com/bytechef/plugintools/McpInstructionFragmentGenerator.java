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

package com.bytechef.plugintools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the management MCP server's instruction fragments from the {@code mcp} transport fences carried by the
 * plugin skills: one {@code ## always} section, plus one {@code ## tools: <name>, <name>, ...} section per fence,
 * naming every tool in that fence's {@code uses:} list and carrying the fence's body exactly once. A fence tagged
 * {@code uses: general} contributes its body to the {@code ## always} section instead of a tools section. Two fences
 * are never merged, even when they name the same tool, so a fence's body is never duplicated in the output.
 *
 * @author Ivica Cardic
 */
public final class McpInstructionFragmentGenerator {

    private static final String GENERAL_MARKER = "general";

    private static final String ALWAYS_HEADING = "## always";

    private static final String TOOLS_HEADING_PREFIX = "## tools: ";

    private static final String ALWAYS_BASE_BODY =
        "ByteChef management server. Ordinary tools are deterministic CRUD; intelligent tools run an inner "
            + "AI agent and may take minutes.";

    private McpInstructionFragmentGenerator() {
    }

    public static void generate(Path skillsDirectory, Path outputFile) throws IOException {
        List<SkillDocument> skillDocuments = SkillDocumentParser.parseAll(skillsDirectory);

        StringBuilder alwaysSection = new StringBuilder(ALWAYS_BASE_BODY);
        List<TransportFence> toolFences = new ArrayList<>();

        for (SkillDocument skillDocument : skillDocuments) {
            for (TransportFence transportFence : skillDocument.fences()) {
                if (transportFence.transport() != Transport.MCP) {
                    continue;
                }

                if (isGeneralFence(transportFence)) {
                    appendBody(alwaysSection, transportFence.body());
                } else {
                    toolFences.add(transportFence);
                }
            }
        }

        String content = renderContent(alwaysSection, toolFences);
        Path parentDirectory = outputFile.getParent();

        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }

        Files.writeString(outputFile, content);
    }

    private static boolean isGeneralFence(TransportFence transportFence) {
        return transportFence.uses()
            .isEmpty()
            || transportFence.uses()
                .contains(GENERAL_MARKER);
    }

    private static void appendBody(StringBuilder section, String body) {
        if (!section.isEmpty()) {
            section.append("\n\n");
        }

        section.append(body.strip());
    }

    private static String renderContent(StringBuilder alwaysSection, List<TransportFence> toolFences) {
        StringBuilder content = new StringBuilder();

        content.append(ALWAYS_HEADING)
            .append('\n')
            .append(alwaysSection)
            .append('\n');

        for (TransportFence toolFence : toolFences) {
            content.append('\n')
                .append(TOOLS_HEADING_PREFIX)
                .append(String.join(", ", toolFence.uses()))
                .append('\n')
                .append(toolFence.body()
                    .strip())
                .append('\n');
        }

        return content.toString();
    }
}
