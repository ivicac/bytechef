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

package com.bytechef.component.zoho.bigin.connection;

import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import com.bytechef.component.zoho.commons.ZohoConnection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class ZohoBiginConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = ZohoConnection.createConnection(
        "/bigin/v2", getScopes(), false, 1,
        "https://docs.bytechef.io/reference/components/zoho-bigin_v1#connection-setup");

    private static Map<String, Boolean> getScopes() {
        Map<String, Boolean> map = new LinkedHashMap<>();

        map.put("ZohoBigin.modules.ALL", true);
        map.put("ZohoBigin.settings.ALL", false);
        map.put("ZohoBigin.users.ALL", false);
        map.put("ZohoBigin.org.ALL", false);
        map.put("ZohoBigin.notifications.ALL", false);

        return map;
    }

    private ZohoBiginConnection() {
    }
}
