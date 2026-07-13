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

package com.bytechef.component.omnisend.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.omnisend.constant.OmnisendConstants.EMAIL;
import static com.bytechef.component.omnisend.constant.OmnisendConstants.FIRST_NAME;
import static com.bytechef.component.omnisend.constant.OmnisendConstants.LAST_NAME;
import static com.bytechef.component.omnisend.constant.OmnisendConstants.STATUS;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class OmnisendCreateContactAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createContact")
        .title("Create Contact")
        .description("Creates a new contact with an email channel.")
        .properties(
            string(EMAIL)
                .label("Email")
                .description("The email address of the contact.")
                .required(true),
            string(STATUS)
                .label("Email Status")
                .description("The subscription status of the email channel.")
                .options(
                    option("Subscribed", "subscribed"),
                    option("Non Subscribed", "nonSubscribed"),
                    option("Unsubscribed", "unsubscribed"))
                .defaultValue("subscribed")
                .required(true),
            string(FIRST_NAME)
                .label("First Name")
                .description("The first name of the contact.")
                .required(false),
            string(LAST_NAME)
                .label("Last Name")
                .description("The last name of the contact.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("contactID")
                            .description("The id of the created contact."),
                        string("email")
                            .description("The email address of the created contact."))))
        .help("", "https://docs.bytechef.io/reference/components/omnisend_v1#create-contact")
        .perform(OmnisendCreateContactAction::perform);

    private OmnisendCreateContactAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(
            "identifiers",
            List.of(
                Map.of(
                    "type", "email",
                    "id", inputParameters.getRequiredString(EMAIL),
                    "channels", Map.of(
                        "email", Map.of(STATUS, inputParameters.getRequiredString(STATUS))))));

        String firstName = inputParameters.getString(FIRST_NAME);

        if (firstName != null) {
            body.put(FIRST_NAME, firstName);
        }

        String lastName = inputParameters.getString(LAST_NAME);

        if (lastName != null) {
            body.put(LAST_NAME, lastName);
        }

        return context.http(http -> http.post("/contacts"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
