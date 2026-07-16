/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guards the invariant that every tool the build system prompt tells the model to call directly by name is pinned in
 * {@link ToolSearchAdvisorConfiguration#ALWAYS_ON_TOOL_NAMES}. When the tool-search advisor is mounted, unpinned static
 * tools are hidden until a {@code searchTool} hit surfaces them, so a directly-called-but-unpinned tool fails at
 * runtime with "No ToolCallback found for tool name: ...". The read-only state-visibility tools of the tool-attach flow
 * are the ones most easily forgotten because they precede the more visible {@code select*} picker tools.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ToolSearchAdvisorConfigurationPinnedToolsTest {

    @Test
    void testToolAttachStateVisibilityToolsArePinned() {
        assertThat(ToolSearchAdvisorConfiguration.ALWAYS_ON_TOOL_NAMES)
            .contains(
                "listTaskTools", "listConnectionsForComponent", "lookupActionPropertyOptions",
                "lookupTriggerPropertyOptions", "selectPropertyOption", "selectTriggerPropertyOption");
    }

    @Test
    void testConnectionPickerToolsArePinned() {
        assertThat(ToolSearchAdvisorConfiguration.ALWAYS_ON_TOOL_NAMES)
            .contains("selectConnection", "createConnection");
    }
}
