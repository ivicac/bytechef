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

package com.bytechef.component.email.octopus.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class EmailOctopusCreateContactAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createContact")
        .title("Create Contact")
        .description("Creates a contact in a mailing list.")
        .properties(
            string("listId")
                .label("List ID")
                .description("The ID of the list to add the contact to.")
                .required(true),
            string("emailAddress")
                .label("Email Address")
                .description("The email address of the contact.")
                .required(true),
            string("firstName")
                .label("First Name")
                .description("The first name of the contact.")
                .required(false),
            string("lastName")
                .label("Last Name")
                .description("The last name of the contact.")
                .required(false),
            string("status")
                .label("Status")
                .description("The initial status of the contact.")
                .options(
                    option("Subscribed", "subscribed"),
                    option("Unsubscribed", "unsubscribed"),
                    option("Pending", "pending"))
                .defaultValue("subscribed")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id"),
                        string("email_address"),
                        string("status"),
                        string("created_at"))))
        .perform(EmailOctopusCreateContactAction::perform);

    private EmailOctopusCreateContactAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put("email_address", inputParameters.getRequiredString("emailAddress"));
        body.put("status", inputParameters.getString("status", "subscribed"));

        Map<String, Object> fields = new HashMap<>();

        if (inputParameters.getString("firstName") != null) {
            fields.put("FirstName", inputParameters.getString("firstName"));
        }

        if (inputParameters.getString("lastName") != null) {
            fields.put("LastName", inputParameters.getString("lastName"));
        }

        if (!fields.isEmpty()) {
            body.put("fields", fields);
        }

        return context
            .http(http -> http.post("/lists/%s/contacts".formatted(inputParameters.getRequiredString("listId"))))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
