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

package com.bytechef.component.azure.blob.storage;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.azure.blob.storage.action.AzureBlobStorageListContainersAction;
import com.bytechef.component.azure.blob.storage.connection.AzureBlobStorageConnection;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class AzureBlobStorageComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("azureBlobStorage")
        .title("Azure Blob Storage")
        .version(1)
        .description("Azure Blob Storage is Microsoft's object storage service for the cloud.")
        .customAction(true)
        .icon("path:assets/azure-blob-storage.svg")
        .categories(ComponentCategory.FILE_STORAGE)
        .connection(AzureBlobStorageConnection.CONNECTION_DEFINITION)
        .actions(AzureBlobStorageListContainersAction.ACTION_DEFINITION)
        .clusterElements(tool(AzureBlobStorageListContainersAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
