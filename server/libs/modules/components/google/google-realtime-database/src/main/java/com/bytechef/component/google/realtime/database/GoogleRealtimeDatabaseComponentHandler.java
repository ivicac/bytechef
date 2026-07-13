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

package com.bytechef.component.google.realtime.database;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.google.realtime.database.action.GoogleRealtimeDatabaseGetDataAction;
import com.bytechef.component.google.realtime.database.connection.GoogleRealtimeDatabaseConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class GoogleRealtimeDatabaseComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("googleRealtimeDatabase")
        .title("Google Cloud Realtime Database")
        .version(1)
        .description("Firebase Realtime Database is a cloud-hosted NoSQL database with realtime synchronization.")
        .customAction(true)
        .icon("path:assets/google-realtime-database.svg")
        .categories(ComponentCategory.DEVELOPER_TOOLS)
        .connection(GoogleRealtimeDatabaseConnection.CONNECTION_DEFINITION)
        .actions(GoogleRealtimeDatabaseGetDataAction.ACTION_DEFINITION)
        .clusterElements(tool(GoogleRealtimeDatabaseGetDataAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
