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

package com.bytechef.automation.ai.mcp.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.graphql.data.method.annotation.QueryMapping;

/**
 * Pins the shape of the MCP-project-workflow GraphQL surface.
 * <p>
 * The {@code @PreAuthorize} guards of the remaining list queries live on {@code McpProjectWorkflowFacade}, not on this
 * controller, and are covered by {@code McpProjectWorkflowFacadeIntTest}. The facade owns them because the service's
 * list reads are also called by trusted internal paths that run without a usable {@code SecurityContext} - the MCP
 * serve path, the delete-cascade listeners, and an agent tool callback on a worker thread - which a service-level guard
 * would reject.
 *
 * @author Ivica Cardic
 */
class McpProjectWorkflowGraphQlAuthorizationTest {

    /**
     * Both were unguarded root queries with no consumer anywhere - not the client, not the server, not the docs. The
     * no-argument one returned every {@code mcp_project_workflow} row and could not be guarded by id at all; the other
     * keyed on a {@code projectDeploymentWorkflowId}, for which no ownership resolver exists, so no
     * {@code hasPermission} expression could be written for it either. Removing them was the only fix that actually
     * closed the exposure rather than checking it.
     */
    @Test
    void testUnguardableRootQueriesAreNotExposed() {
        assertThat(McpProjectWorkflowGraphQlController.class.getDeclaredMethods())
            .noneMatch(
                method -> method.isAnnotationPresent(QueryMapping.class) &&
                    (method.getName()
                        .equals("mcpProjectWorkflows") ||
                        method.getName()
                            .equals("mcpProjectWorkflowsByProjectDeploymentWorkflowId")));
    }
}
