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

package com.bytechef.automation.data.table.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} expressions that workspace-scope data-table operations (T22), enforced at the facade
 * tier. Per-table DDL resolves the owning workspace via {@code DataTable:ResourceRole} (EDITOR); create/list take a
 * {@code workspaceId} argument.
 *
 * @author Ivica Cardic
 */
class WorkspaceDataTableFacadeAuthorizationTest {

    @Test
    void testCreateRequiresWorkspaceEditorInTheNamedEnvironment() {
        assertExpression(
            "createTable", "hasWorkspaceScopeInEnvironmentId(#workspaceId, 'DATA_TABLE_CREATE', #environmentId)");
    }

    @Test
    void testListRequiresWorkspaceViewerInTheNamedEnvironment() {
        assertExpression(
            "listTables", "hasWorkspaceScopeInEnvironmentId(#workspaceId, 'DATA_TABLE_VIEW', #environmentId)");
    }

    @Test
    void testGetDataTableTagsRequiresWorkspaceViewer() {
        assertExpression("getDataTableTags", "hasPermission(#workspaceId, 'Workspace', 'DATA_TABLE_VIEW')");
    }

    @Test
    void testAddColumnRequiresTableEditor() {
        assertExpression(
            "addColumn",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testDropRequiresTableEditor() {
        assertExpression(
            "dropTable",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testDuplicateRequiresTableEditor() {
        assertExpression(
            "duplicateTable",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testRemoveColumnRequiresTableEditor() {
        assertExpression(
            "removeColumn",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testRenameColumnRequiresTableEditor() {
        assertExpression(
            "renameColumn",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testRenameTableRequiresTableEditor() {
        assertExpression(
            "renameTable",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testListRowsRequiresTableViewer() {
        assertExpression(
            "listRows",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW', #environmentId)");
    }

    @Test
    void testInsertRowRequiresTableEditor() {
        assertExpression(
            "insertRow",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testUpdateRowRequiresTableEditor() {
        assertExpression(
            "updateRow",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testDeleteRowRequiresTableEditor() {
        assertExpression(
            "deleteRow",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testExportCsvRequiresTableViewer() {
        assertExpression(
            "exportCsv",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW', #environmentId)");
    }

    @Test
    void testImportCsvRequiresTableEditor() {
        assertExpression(
            "importCsv",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testUpdateTagsRequiresTableEditor() {
        assertExpression("updateTags", "hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')");
    }

    @Test
    void testListWebhooksRequiresTableViewer() {
        assertExpression(
            "listWebhooks",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW', #environmentId)");
    }

    @Test
    void testGetTableRequiresTableViewer() {
        assertExpression(
            "getTable",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW', #environmentId)");
    }

    @Test
    void testUpdateDescriptionRequiresTableEditor() {
        assertExpression("updateDescription", "hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')");
    }

    @Test
    void testListRowsOverloadsBothRequireTableViewer() {
        assertExpression(
            "listRows",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW', #environmentId)");
    }

    @Test
    void testGetRowRequiresTableViewer() {
        assertExpression(
            "getRow",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW', #environmentId)");
    }

    @Test
    void testFetchRowByExternalIdRequiresTableViewer() {
        assertExpression(
            "fetchRowByExternalId",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW', #environmentId)");
    }

    @Test
    void testInsertRowOverloadsBothRequireTableEditor() {
        assertExpression(
            "insertRow",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testUpdateRowOverloadsBothRequireTableEditor() {
        assertExpression(
            "updateRow",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testUpsertRowRequiresTableEditor() {
        assertExpression(
            "upsertRow",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testDeleteRowByExternalIdRequiresTableEditor() {
        assertExpression(
            "deleteRowByExternalId",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testInsertRowsRequiresTableEditor() {
        assertExpression(
            "insertRows",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testDeleteRowsRequiresTableEditor() {
        assertExpression(
            "deleteRows",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testClearRowsRequiresTableEditor() {
        assertExpression(
            "clearRows",
            "hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT', #environmentId)");
    }

    @Test
    void testGetTagsByTableIdRequiresWorkspaceViewer() {
        assertExpression("getTagsByTableId", "hasPermission(#workspaceId, 'Workspace', 'DATA_TABLE_VIEW')");
    }

    @Test
    void testGetWorkspaceIdRequiresTableViewer() {
        assertExpression("getWorkspaceId", "hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW')");
    }

    /**
     * Asserts the expression on EVERY declared method with this name, not just the first one {@code getDeclaredMethods}
     * happens to return -- {@code getDeclaredMethods} order is unspecified, and several of these names are overloaded
     * (e.g. {@code listRows}, {@code insertRow}, {@code updateRow}), so checking only one match would silently skip the
     * other overload's guard.
     */
    private static void assertExpression(String methodName, String expression) {
        List<Method> methods = new ArrayList<>();

        for (Method candidate : WorkspaceDataTableFacadeImpl.class.getDeclaredMethods()) {
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
