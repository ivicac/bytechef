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

package com.bytechef.component.tiktok.connection;

import static com.bytechef.component.definition.Authorization.CLIENT_ID;
import static com.bytechef.component.definition.Authorization.CLIENT_SECRET;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class TiktokConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://open.tiktokapis.com/v2")
        .authorizations(
            authorization(AuthorizationType.OAUTH2_AUTHORIZATION_CODE)
                .title("OAuth2 Authorization Code")
                .properties(
                    string(CLIENT_ID)
                        .label("Client Key")
                        .description("The client key of your TikTok developer app.")
                        .required(true),
                    string(CLIENT_SECRET)
                        .label("Client Secret")
                        .description("The client secret of your TikTok developer app.")
                        .required(true))
                .authorizationUrl((connectionParameters, context) -> "https://www.tiktok.com/v2/auth/authorize/")
                .scopes((connectionParameters, context) -> Map.of(
                    "user.info.basic", true, "user.info.profile", true, "user.info.stats", true, "video.list", true))
                .tokenUrl((connectionParameters, context) -> "https://open.tiktokapis.com/v2/oauth/token/")
                .refreshUrl((connectionParameters, context) -> "https://open.tiktokapis.com/v2/oauth/token/"));

    private TiktokConnection() {
    }
}
