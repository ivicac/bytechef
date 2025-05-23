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

package com.bytechef.component.active.campaign.action;

import static com.bytechef.component.OpenApiComponentHandler.PropertyType;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.BodyContentType;
import static com.bytechef.component.definition.Context.Http.ResponseType;

import com.bytechef.component.definition.ComponentDsl;
import java.util.Map;

/**
 * Provides a list of the component actions.
 *
 * @generated
 */
public class ActiveCampaignCreateAccountAction {
    public static final ComponentDsl.ModifiableActionDefinition ACTION_DEFINITION = action("createAccount")
        .title("Create Account")
        .description("Creates a new account.")
        .metadata(
            Map.of(
                "method", "POST",
                "path", "/accounts", "bodyContentType", BodyContentType.JSON, "mimeType", "application/json"

            ))
        .properties(object("account").properties(string("name").label("Name")
            .description("Account's name")
            .required(true),
            string("accountUrl").label("Website")
                .description("Account's website")
                .required(false))
            .metadata(
                Map.of(
                    "type", PropertyType.BODY))
            .label("Account")
            .required(false))
        .output(outputSchema(object()
            .properties(object("account").properties(string("name").description("Name of the account.")
                .required(false),
                string("accountUrl").description("Website of the account.")
                    .required(false),
                string("id").description("ID of the account.")
                    .required(false))
                .required(false))
            .metadata(
                Map.of(
                    "responseType", ResponseType.JSON))));

    private ActiveCampaignCreateAccountAction() {
    }
}
