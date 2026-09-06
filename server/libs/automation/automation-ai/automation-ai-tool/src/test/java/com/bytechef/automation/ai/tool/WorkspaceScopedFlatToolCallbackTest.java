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

package com.bytechef.automation.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.automation.configuration.domain.Workspace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 *
 * @author Ivica Cardic
 */
class WorkspaceScopedFlatToolCallbackTest {

    private ContextCapturingDelegate delegate;
    private AccessibleWorkspaceResolver accessibleWorkspaceResolver;
    private WorkspaceScopedFlatToolCallback toolCallback;

    @BeforeEach
    void beforeEach() {
        delegate = new ContextCapturingDelegate();
        accessibleWorkspaceResolver = mock(AccessibleWorkspaceResolver.class);
        toolCallback = new WorkspaceScopedFlatToolCallback(delegate, accessibleWorkspaceResolver);

        when(accessibleWorkspaceResolver.isAccessible(anyLong())).thenReturn(true);
    }

    @Test
    void testToolDefinitionKeepsDelegateNameAndExtendsSchemaWithoutDroppingOriginalProperties() {
        ToolDefinition toolDefinition = toolCallback.getToolDefinition();

        assertThat(toolDefinition.name()).isEqualTo("createMcpServer");
        assertThat(toolDefinition.inputSchema())
            .contains("workspaceId")
            .contains("environment")
            .contains("name")
            .contains("enabled");
    }

    @Test
    void testExplicitWorkspaceIdIsForwardedAndOtherFieldsSurviveIntact() {
        String result =
            toolCallback.call("{\"name\": \"my server\", \"environment\": \"STAGING\", \"workspaceId\": 42}");

        assertThat(result).isEqualTo("done");
        assertThat(delegate.capturedContext)
            .containsEntry(AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY, 42L)
            .containsEntry(AutomationToolInvocationContext.TOOL_CONTEXT_ENVIRONMENT_ID_KEY, 1L);
        assertThat(delegate.capturedInput)
            .contains("my server")
            .doesNotContain("workspaceId")
            .doesNotContain("STAGING");
    }

    @Test
    void testDefaultsEnvironmentToDevelopmentWhenOmitted() {
        toolCallback.call("{\"name\": \"my server\", \"workspaceId\": 42}");

        assertThat(delegate.capturedContext)
            .containsEntry(AutomationToolInvocationContext.TOOL_CONTEXT_ENVIRONMENT_ID_KEY, 0L);
    }

    @Test
    void testUnknownEnvironmentReturnsError() {
        String result = toolCallback.call("{\"name\": \"my server\", \"workspaceId\": 42, \"environment\": \"NOPE\"}");

        assertThat(result).contains("error")
            .contains("Unknown environment")
            .contains("NOPE");
        assertThat(delegate.capturedContext).isNull();
    }

    @Test
    void testSingleWorkspaceIsAutoSelected() {
        Workspace workspace = new Workspace();

        workspace.setId(7L);
        workspace.setName("Main");

        when(accessibleWorkspaceResolver.getAccessibleWorkspaces()).thenReturn(List.of(workspace));

        String result = toolCallback.call("{\"name\": \"my server\"}");

        assertThat(result).isEqualTo("done");
        assertThat(delegate.capturedContext)
            .containsEntry(AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY, 7L);
    }

    @Test
    void testMultipleWorkspacesReturnCandidateList() {
        Workspace firstWorkspace = new Workspace();

        firstWorkspace.setId(1L);
        firstWorkspace.setName("Alpha");

        Workspace secondWorkspace = new Workspace();

        secondWorkspace.setId(2L);
        secondWorkspace.setName("Beta");

        when(accessibleWorkspaceResolver.getAccessibleWorkspaces())
            .thenReturn(List.of(firstWorkspace, secondWorkspace));

        String result = toolCallback.call("{\"name\": \"my server\"}");

        assertThat(result).contains("workspace_required")
            .contains("Alpha")
            .contains("Beta");
        assertThat(delegate.capturedContext).isNull();
    }

    /**
     * Every tool wrapped by this class gets the FILES source ordinal written unconditionally — a no-op for the tools
     * that never create asset files, but it is what lets the flat asset-file create tools ({@code createAssetFile},
     * {@code createBinaryAssetFile}, {@code createAssetFileFromUrl}) attribute a file created through the management
     * MCP server to the Files surface (see this class's Javadoc).
     */
    @Test
    void testForwardsSourceOrdinalUnconditionally() {
        toolCallback.call("{\"name\": \"my server\", \"workspaceId\": 42}");

        assertThat(delegate.capturedContext)
            .containsEntry(
                AutomationToolInvocationContext.TOOL_CONTEXT_SOURCE_ORDINAL_KEY,
                AutomationToolInvocationContext.SOURCE_ORDINAL_FILES);
    }

    /**
     * Ticket 732, CRUD-delegate-unwind Task 5: the flat data-table tools this wrapper started covering read
     * {@link AgentToolInvocationContext}'s key family, not {@link AutomationToolInvocationContext}'s — so both must be
     * written unconditionally, mirroring {@code WorkspaceScopedSubAgentToolCallback}'s existing dual-write.
     */
    @Test
    void testForwardsAgentToolInvocationContextFamilyUnconditionally() {
        toolCallback.call("{\"name\": \"my server\", \"environment\": \"STAGING\", \"workspaceId\": 42}");

        assertThat(delegate.capturedContext)
            .containsEntry(AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY, 42L)
            .containsEntry(AgentToolInvocationContext.TOOL_CONTEXT_ENVIRONMENT_ID_KEY, 1L);
    }

    @Test
    void testEmptyInputToolIsHandledWithoutError() {
        Workspace workspace = new Workspace();

        workspace.setId(9L);
        workspace.setName("Main");

        when(accessibleWorkspaceResolver.getAccessibleWorkspaces()).thenReturn(List.of(workspace));

        String result = toolCallback.call("{}");

        assertThat(result).isEqualTo("done");
        assertThat(delegate.capturedContext)
            .containsEntry(AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY, 9L);
    }

    /**
     * Fake delegate that records the forwarded input and ToolContext and answers directly, so the wrapper's behaviour
     * is observable without a real facade.
     */

    @Test
    void testExplicitWorkspaceIdTheCallerCannotReachIsRefused() {
        when(accessibleWorkspaceResolver.isAccessible(99L)).thenReturn(false);

        String result = toolCallback.call("{\"name\": \"my server\", \"workspaceId\": 99}");

        assertThat(result)
            .as("the management MCP key binds the caller to no workspace, so an unchecked id here reached tools "
                + "whose facade has no authorization of its own")
            .contains("not accessible");
        assertThat(delegate.capturedContext)
            .as("the delegate must not run at all for a workspace the caller cannot reach")
            .isNull();
    }

    @Test
    void testRefusalDoesNotRevealWhetherTheWorkspaceExists() {
        when(accessibleWorkspaceResolver.isAccessible(99L)).thenReturn(false);

        String result = toolCallback.call("{\"name\": \"my server\", \"workspaceId\": 99}");

        assertThat(result)
            .as("a distinguishable 'no such workspace' would let a caller probe which ids are real")
            .doesNotContain("99")
            .doesNotContain("not found")
            .doesNotContain("does not exist");
    }

    @Test
    void testCandidateListOffersOnlyWorkspacesTheCallerCanReach() {
        Workspace ownWorkspace = new Workspace();

        ownWorkspace.setId(1L);
        ownWorkspace.setName("Alpha");

        Workspace otherOwnWorkspace = new Workspace();

        otherOwnWorkspace.setId(2L);
        otherOwnWorkspace.setName("Beta");

        when(accessibleWorkspaceResolver.getAccessibleWorkspaces())
            .thenReturn(List.of(ownWorkspace, otherOwnWorkspace));

        String result = toolCallback.call("{\"name\": \"my server\"}");

        assertThat(result)
            .as("this listing used to come from a tenant-wide findAll(), handing the caller every workspace's id "
                + "and name as a target list")
            .contains("Alpha")
            .contains("Beta");
    }

    private static final class ContextCapturingDelegate implements ToolCallback {

        private Map<String, Object> capturedContext;
        private String capturedInput;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("createMcpServer")
                .description("Create a new MCP server.")
                .inputSchema(
                    "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, "
                        + "\"environment\": {\"type\": \"string\"}, \"enabled\": {\"type\": \"boolean\"}}, "
                        + "\"required\": [\"name\", \"environment\"]}")
                .build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            capturedInput = toolInput;
            capturedContext = toolContext == null ? null : toolContext.getContext();

            return "done";
        }
    }
}
