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

package com.bytechef.component.google.ads.connection;

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
public class GoogleAdsConnection {

    public static final String DEVELOPER_TOKEN = "developerToken";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://googleads.googleapis.com/v19")
        .authorizations(
            authorization(AuthorizationType.OAUTH2_AUTHORIZATION_CODE)
                .title("OAuth2 Authorization Code")
                .properties(
                    string(CLIENT_ID)
                        .label("Client Id")
                        .required(true),
                    string(CLIENT_SECRET)
                        .label("Client Secret")
                        .required(true),
                    string(DEVELOPER_TOKEN)
                        .label("Developer Token")
                        .description("The Google Ads API developer token from your manager account.")
                        .required(true))
                .authorizationUrl((connectionParameters, context) -> "https://accounts.google.com/o/oauth2/auth")
                .oAuth2AuthorizationExtraQueryParameters(
                    Map.of("access_type", "offline", "prompt", "select_account consent"))
                .scopes((connectionParameters, context) -> Map.of("https://www.googleapis.com/auth/adwords", true))
                .tokenUrl((connectionParameters, context) -> "https://oauth2.googleapis.com/token")
                .refreshUrl((connectionParameters, context) -> "https://oauth2.googleapis.com/token"));

    private GoogleAdsConnection() {
    }
}
