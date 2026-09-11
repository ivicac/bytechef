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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the management MCP server's instruction fragments from the {@code mcp} transport fences carried by the
 * plugin skills: one {@code ## always} section, plus one {@code ## tool: <name>} section per distinct tool named by a
 * fence's {@code uses:} list. A fence tagged {@code uses: general} contributes its body to the {@code ## always}
 * section instead of a tool section. A tool named by several fences gets its bodies concatenated, in file order, under
 * one section.
 *
 * @author Ivica Cardic
 */
public final class McpInstructionFragmentGenerator {

    private static final String GENERAL_MARKER = "general";

    private static final String ALWAYS_HEADING = "## always";

    private static final String TOOL_HEADING_PREFIX = "## tool: ";

    private static final String ALWAYS_BASE_BODY =
        "ByteChef management server. Ordinary tools are deterministic CRUD; intelligent tools run an inner "
            + "AI agent and may take minutes.";

    private McpInstructionFragmentGenerator() {
    }

    public static void generate(Path skillsDirectory, Path outputFile) throws IOException {
        List<SkillDocument> skillDocuments = SkillDocumentParser.parseAll(skillsDirectory);

        StringBuilder alwaysSection = new StringBuilder(ALWAYS_BASE_BODY);
        Map<String, StringBuilder> toolSections = new LinkedHashMap<>();

        for (SkillDocument skillDocument : skillDocuments) {
            for (TransportFence transportFence : skillDocument.fences()) {
                if (transportFence.transport() != Transport.MCP) {
                    continue;
                }

                if (isGeneralFence(transportFence)) {
                    appendBody(alwaysSection, transportFence.body());

                    continue;
                }

                for (String toolName : transportFence.uses()) {
                    StringBuilder toolSection = toolSections.computeIfAbsent(toolName, key -> new StringBuilder());

                    appendBody(toolSection, transportFence.body());
                }
            }
        }

        String content = renderContent(alwaysSection, toolSections);
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

    private static String renderContent(StringBuilder alwaysSection, Map<String, StringBuilder> toolSections) {
        StringBuilder content = new StringBuilder();

        content.append(ALWAYS_HEADING)
            .append('\n')
            .append(alwaysSection)
            .append('\n');

        for (Map.Entry<String, StringBuilder> toolSectionEntry : toolSections.entrySet()) {
            content.append('\n')
                .append(TOOL_HEADING_PREFIX)
                .append(toolSectionEntry.getKey())
                .append('\n')
                .append(toolSectionEntry.getValue())
                .append('\n');
        }

        return content.toString();
    }
}
