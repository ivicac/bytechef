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

package com.bytechef.platform.data.table.configuration.service;

import com.bytechef.definition.BaseProperty.ResourceType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.platform.workflow.validator.ResourceReferenceResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * @author Ivica Cardic
 */
@Component
public class DataTableReferenceResolver implements ResourceReferenceResolver {

    /**
     * The pools a reference may resolve in. Validation runs in the editor, where there is no connected user -- the
     * vendor case, which {@code DataTableUtils.poolFor} admits to BOTH pools -- so this mirrors what
     * {@code DataTableUtils.resolveDataTable} will do with the same name at run time. A data table reference is a base
     * NAME, not an id, and the pool is part of the physical table's name, so the same name is a different table in each
     * pool.
     */
    private static final List<PlatformType> EDITOR_POOLS = List.of(PlatformType.AUTOMATION, PlatformType.EMBEDDED);

    private final DataTableRowService dataTableRowService;
    private final DataTableService dataTableService;

    @SuppressFBWarnings("EI")
    public DataTableReferenceResolver(DataTableRowService dataTableRowService, DataTableService dataTableService) {
        this.dataTableRowService = dataTableRowService;
        this.dataTableService = dataTableService;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.DATA_TABLE;
    }

    @Override
    @Nullable
    public String findProblem(String reference, long environmentId) {
        Map<PlatformType, DataTableInfo> dataTableInfosByPool = findTables(reference, environmentId);

        if (dataTableInfosByPool.isEmpty()) {
            return "Data table '" + reference + "' does not exist in this environment";
        }

        if (dataTableInfosByPool.size() > 1) {
            // Exactly what resolveDataTable refuses at run time: the same base name is legal in both pools after the
            // split and nothing tells them apart, so it rejects rather than picking one. Saying so here is the
            // difference between learning it in the editor and learning it mid-run.
            return "Data table '" + reference + "' exists in more than one data table pool in this environment, so a " +
                "run cannot tell which one is meant";
        }

        Map.Entry<PlatformType, DataTableInfo> dataTableInfoEntry = dataTableInfosByPool.entrySet()
            .iterator()
            .next();

        return findRowProblem(dataTableInfoEntry.getValue(), environmentId, dataTableInfoEntry.getKey());
    }

    private Map<PlatformType, DataTableInfo> findTables(String reference, long environmentId) {
        Map<PlatformType, DataTableInfo> dataTableInfosByPool = new LinkedHashMap<>();

        for (PlatformType platformType : EDITOR_POOLS) {
            DataTableInfo dataTableInfo = findTable(reference, environmentId, platformType);

            if (dataTableInfo != null) {
                dataTableInfosByPool.put(platformType, dataTableInfo);
            }
        }

        return dataTableInfosByPool;
    }

    private @Nullable String findRowProblem(
        DataTableInfo dataTableInfo, long environmentId, PlatformType platformType) {

        try {
            // The base name comes from the table that was found rather than from the reference, so the ref is well
            // formed by construction -- a DataTableRef validates its base name, and a reference typed into a workflow
            // need not be a legal identifier at all.
            dataTableRowService.listRows(
                DataTableRef.unowned(dataTableInfo.baseName(), environmentId, platformType), 1, 0);
        } catch (IllegalStateException illegalStateException) {
            return illegalStateException.getMessage();
        }

        return null;
    }

    private @Nullable DataTableInfo findTable(String reference, long environmentId, PlatformType platformType) {
        return dataTableService.listTables(environmentId, platformType)
            .stream()
            .filter(dataTableInfo -> reference.equalsIgnoreCase(dataTableInfo.baseName()))
            .findFirst()
            .orElse(null);
    }
}
