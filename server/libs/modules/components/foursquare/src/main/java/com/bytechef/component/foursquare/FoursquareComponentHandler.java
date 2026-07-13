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

package com.bytechef.component.foursquare;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.foursquare.action.FoursquareSearchPlacesAction;
import com.bytechef.component.foursquare.connection.FoursquareConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class FoursquareComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("foursquare")
        .title("Foursquare")
        .version(1)
        .description("Foursquare provides location data and place search for points of interest worldwide.")
        .customAction(true)
        .icon("path:assets/foursquare.svg")
        .categories(ComponentCategory.HELPERS)
        .connection(FoursquareConnection.CONNECTION_DEFINITION)
        .actions(FoursquareSearchPlacesAction.ACTION_DEFINITION)
        .clusterElements(tool(FoursquareSearchPlacesAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
