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

package com.bytechef.component.facebook.pages.connection;

import static com.bytechef.component.definition.Authorization.CLIENT_ID;
import static com.bytechef.component.definition.Authorization.CLIENT_SECRET;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class FacebookPagesConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://graph.facebook.com/v19.0")
        .authorizations(
            authorization(AuthorizationType.OAUTH2_AUTHORIZATION_CODE)
                .title("OAuth2 Authorization Code")
                .properties(
                    string(CLIENT_ID)
                        .label("App Id")
                        .required(true),
                    string(CLIENT_SECRET)
                        .label("App Secret")
                        .required(true))
                .authorizationUrl((connectionParameters, context) -> "https://www.facebook.com/v19.0/dialog/oauth")
                .tokenUrl((connectionParameters, context) -> "https://graph.facebook.com/v19.0/oauth/access_token")
                .scopes((connectionParameters, context) -> getScopes()))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/facebookPages_v1#connection-setup");

    private static Map<String, Boolean> getScopes() {
        Map<String, Boolean> map = new LinkedHashMap<>();

        map.put("pages_show_list", true);
        map.put("pages_read_engagement", true);
        map.put("pages_manage_posts", true);
        map.put("business_management", true);

        return map;
    }

    private FacebookPagesConnection() {
    }
}
