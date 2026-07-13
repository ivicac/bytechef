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

package com.bytechef.component.action.network.action;

import static com.bytechef.component.action.network.constant.ActionNetworkConstants.EMAIL;
import static com.bytechef.component.action.network.constant.ActionNetworkConstants.FAMILY_NAME;
import static com.bytechef.component.action.network.constant.ActionNetworkConstants.GIVEN_NAME;
import static com.bytechef.component.action.network.constant.ActionNetworkConstants.PERSON;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

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
public class ActionNetworkCreatePersonAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createPerson")
        .title("Create Person")
        .description("Creates or updates a person in your Action Network group.")
        .properties(
            string(EMAIL)
                .label("Email")
                .description("The email address of the person.")
                .required(true),
            string(GIVEN_NAME)
                .label("First Name")
                .description("The first name of the person.")
                .required(false),
            string(FAMILY_NAME)
                .label("Last Name")
                .description("The last name of the person.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("identifiers")
                            .description("The identifiers of the person.")
                            .items(string()),
                        string("given_name")
                            .description("The first name of the person."),
                        string("family_name")
                            .description("The last name of the person."),
                        string("created_date")
                            .description("The date the person was created."),
                        array("email_addresses")
                            .description("The email addresses of the person.")
                            .items(
                                object()
                                    .properties(
                                        string("address")
                                            .description("The email address."),
                                        string("status")
                                            .description("The subscription status of the email address."))))))
        .help("", "https://docs.bytechef.io/reference/components/actionNetwork_v1#create-person")
        .perform(ActionNetworkCreatePersonAction::perform);

    private ActionNetworkCreatePersonAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> person = new HashMap<>();

        person.put("email_addresses", List.of(Map.of("address", inputParameters.getRequiredString(EMAIL))));

        String givenName = inputParameters.getString(GIVEN_NAME);

        if (givenName != null) {
            person.put(GIVEN_NAME, givenName);
        }

        String familyName = inputParameters.getString(FAMILY_NAME);

        if (familyName != null) {
            person.put(FAMILY_NAME, familyName);
        }

        return context.http(http -> http.post("/people"))
            .body(Body.of(Map.of(PERSON, person)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
