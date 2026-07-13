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

package com.bytechef.component.drift;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.drift.action.DriftCreateContactAction;
import com.bytechef.component.drift.action.DriftListContactsAction;
import com.bytechef.component.drift.connection.DriftConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class DriftComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("drift")
        .title("Drift")
        .version(1)
        .description("Drift is a conversational marketing and sales platform.")
        .customAction(true)
        .icon("path:assets/drift.svg")
        .categories(ComponentCategory.CRM)
        .connection(DriftConnection.CONNECTION_DEFINITION)
        .actions(
            DriftListContactsAction.ACTION_DEFINITION,
            DriftCreateContactAction.ACTION_DEFINITION)
        .clusterElements(tool(DriftCreateContactAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
