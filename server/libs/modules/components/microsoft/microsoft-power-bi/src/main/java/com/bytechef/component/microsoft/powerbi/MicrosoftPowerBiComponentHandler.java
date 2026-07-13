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

package com.bytechef.component.microsoft.powerbi;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.microsoft.powerbi.action.MicrosoftPowerBiListDatasetsAction;
import com.bytechef.component.microsoft.powerbi.connection.MicrosoftPowerBiConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class MicrosoftPowerBiComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("microsoftPowerBi")
        .title("Microsoft Power BI")
        .version(1)
        .description("Microsoft Power BI is a business analytics platform for interactive dashboards and reports.")
        .customAction(true)
        .icon("path:assets/microsoft-power-bi.svg")
        .categories(ComponentCategory.ANALYTICS)
        .connection(MicrosoftPowerBiConnection.CONNECTION_DEFINITION)
        .actions(MicrosoftPowerBiListDatasetsAction.ACTION_DEFINITION)
        .clusterElements(tool(MicrosoftPowerBiListDatasetsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
