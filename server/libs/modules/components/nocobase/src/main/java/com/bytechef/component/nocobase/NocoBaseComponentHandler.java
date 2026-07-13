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

package com.bytechef.component.nocobase;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.nocobase.action.NocoBaseCreateRecordAction;
import com.bytechef.component.nocobase.connection.NocoBaseConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class NocoBaseComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("nocobase")
        .title("NocoBase")
        .version(1)
        .description("NocoBase is an open-source no-code platform for building business applications.")
        .customAction(true)
        .icon("path:assets/nocobase.svg")
        .categories(ComponentCategory.DEVELOPER_TOOLS)
        .connection(NocoBaseConnection.CONNECTION_DEFINITION)
        .actions(NocoBaseCreateRecordAction.ACTION_DEFINITION)
        .clusterElements(tool(NocoBaseCreateRecordAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
