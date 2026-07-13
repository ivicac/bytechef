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

package com.bytechef.component.google.realtime.database.connection;

import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import com.bytechef.google.commons.GoogleConnection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class GoogleRealtimeDatabaseConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = GoogleConnection.createConnection(
        "https://firebasedatabase.googleapis.com", 1,
        "https://docs.bytechef.io/reference/components/googleRealtimeDatabase_v1#connection-setup",
        (connectionParameters, context) -> getScopes());

    private static Map<String, Boolean> getScopes() {
        Map<String, Boolean> map = new LinkedHashMap<>();

        map.put("https://www.googleapis.com/auth/userinfo.email", true);
        map.put("https://www.googleapis.com/auth/firebase.database", true);

        return map;
    }

    private GoogleRealtimeDatabaseConnection() {
    }
}
