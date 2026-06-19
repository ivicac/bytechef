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

package com.bytechef.automation.data.table.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} expressions that workspace-scope data-table DDL (T22). Per-table mutations resolve the
 * owning workspace via {@code DataTable:ResourceRole} (EDITOR); create/list take a {@code workspaceId} argument.
 *
 * @author Ivica Cardic
 */
class DataTableGraphQlControllerAuthorizationTest {

    @Test
    void testCreateRequiresWorkspaceEditor() {
        assertExpression("createDataTable", "hasPermission(#input.workspaceId, 'WorkspaceRole', 'EDITOR')");
    }

    @Test
    void testDataTablesRequiresWorkspaceViewer() {
        assertExpression("dataTables", "hasPermission(#workspaceId, 'WorkspaceRole', 'VIEWER')");
    }

    @Test
    void testAddColumnRequiresTableEditor() {
        assertExpression("addDataTableColumn", "hasPermission(#input.tableId, 'DataTable:ResourceRole', 'EDITOR')");
    }

    @Test
    void testDropRequiresTableEditor() {
        assertExpression("dropDataTable", "hasPermission(#input.tableId, 'DataTable:ResourceRole', 'EDITOR')");
    }

    @Test
    void testDuplicateRequiresTableEditor() {
        assertExpression("duplicateDataTable", "hasPermission(#input.tableId, 'DataTable:ResourceRole', 'EDITOR')");
    }

    @Test
    void testRemoveColumnRequiresTableEditor() {
        assertExpression("removeDataTableColumn", "hasPermission(#input.tableId, 'DataTable:ResourceRole', 'EDITOR')");
    }

    @Test
    void testRenameColumnRequiresTableEditor() {
        assertExpression("renameDataTableColumn", "hasPermission(#input.tableId, 'DataTable:ResourceRole', 'EDITOR')");
    }

    @Test
    void testRenameTableRequiresTableEditor() {
        assertExpression("renameDataTable", "hasPermission(#input.tableId, 'DataTable:ResourceRole', 'EDITOR')");
    }

    private static void assertExpression(String methodName, String expression) {
        Method method = null;

        for (Method candidate : DataTableGraphQlController.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals(methodName)) {
                method = candidate;

                break;
            }
        }

        assertThat(method)
            .as("method %s", methodName)
            .isNotNull();

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("@PreAuthorize on %s", methodName)
            .isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(expression);
    }
}
