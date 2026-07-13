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

package com.bytechef.component.microsoft.planner.connection;

import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import com.bytechef.microsoft.commons.MicrosoftConnection;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class MicrosoftPlannerConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = MicrosoftConnection.createConnection(
        1,
        "https://docs.bytechef.io/reference/components/microsoft-planner_v1#connection-setup",
        (connection, context) -> Map.of(
            "Tasks.Read", true,
            "Tasks.ReadWrite", true,
            "Group.Read.All", false,
            "offline_access", true));

    private MicrosoftPlannerConnection() {
    }
}
