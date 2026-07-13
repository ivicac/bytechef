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

package com.bytechef.component.aircall;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.aircall.action.AircallCreateContactAction;
import com.bytechef.component.aircall.action.AircallListContactsAction;
import com.bytechef.component.aircall.connection.AircallConnection;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class AircallComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("aircall")
        .title("Aircall")
        .version(1)
        .description(
            "Aircall is a cloud-based phone system and call-center software for support and sales teams.")
        .customAction(true)
        .icon("path:assets/aircall.svg")
        .categories(ComponentCategory.COMMUNICATION)
        .connection(AircallConnection.CONNECTION_DEFINITION)
        .actions(
            AircallListContactsAction.ACTION_DEFINITION,
            AircallCreateContactAction.ACTION_DEFINITION)
        .clusterElements(tool(AircallCreateContactAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
