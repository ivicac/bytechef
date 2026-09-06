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

package com.bytechef.automation.ai.mcp.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} expressions that workspace-scope MCP server operations (T20), enforced at the facade
 * tier. Per-server delete resolves the owning workspace via {@code McpServer:ResourceRole}; list/create take a
 * {@code workspaceId} argument. {@code createWorkspaceMcpServer} is overloaded and both overloads already hold a
 * resolved {@link com.bytechef.platform.configuration.domain.Environment}, so both are asserted.
 *
 * @author Ivica Cardic
 */
class WorkspaceMcpServerFacadeAuthorizationTest {

    @Test
    void testGetWorkspaceMcpServersRequiresViewer() {
        assertExpression("getWorkspaceMcpServers", "hasPermission(#workspaceId, 'Workspace', 'MCP_VIEW')");
    }

    @Test
    void testGetWorkspaceMcpServerTagsRequiresViewer() {
        assertExpression("getWorkspaceMcpServerTags", "hasPermission(#workspaceId, 'Workspace', 'MCP_VIEW')");
    }

    @Test
    void testGetWorkspaceMcpProjectsRequiresViewer() {
        assertExpression("getWorkspaceMcpProjects", "hasPermission(#workspaceId, 'Workspace', 'MCP_VIEW')");
    }

    @Test
    void testCreateRequiresEditorInTheNamedEnvironment() {
        assertExpression(
            "createWorkspaceMcpServer", "hasWorkspaceScopeInEnvironment(#workspaceId, 'MCP_CREATE', #environment)");
    }

    @Test
    void testDeleteRequiresServerEditor() {
        assertExpression("deleteWorkspaceMcpServer", "hasPermission(#mcpServerId, 'McpServer', 'MCP_EDIT')");
    }

    /**
     * Asserts the expression on EVERY declared method with this name, not just the first one {@code getDeclaredMethods}
     * happens to return -- {@code getDeclaredMethods} order is unspecified, and {@code createWorkspaceMcpServer} is
     * overloaded, so checking only one match would silently skip the other overload's guard.
     */
    private static void assertExpression(String methodName, String expression) {
        List<Method> methods = new ArrayList<>();

        for (Method candidate : WorkspaceMcpServerFacadeImpl.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals(methodName)) {
                methods.add(candidate);
            }
        }

        assertThat(methods)
            .as("method %s", methodName)
            .isNotEmpty();

        for (Method method : methods) {
            PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

            assertThat(preAuthorize)
                .as("@PreAuthorize on %s%s", methodName, Arrays.toString(method.getParameterTypes()))
                .isNotNull();
            assertThat(preAuthorize.value())
                .as("@PreAuthorize value on %s%s", methodName, Arrays.toString(method.getParameterTypes()))
                .isEqualTo(expression);
        }
    }
}
