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

package com.bytechef.component.teamleader.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.teamleader.constant.TeamleaderConstants.EMAIL;
import static com.bytechef.component.teamleader.constant.TeamleaderConstants.FIRST_NAME;
import static com.bytechef.component.teamleader.constant.TeamleaderConstants.LAST_NAME;

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
public class TeamleaderCreateContactAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createContact")
        .title("Create Contact")
        .description("Creates a new contact in Teamleader Focus.")
        .properties(
            string(LAST_NAME)
                .label("Last Name")
                .description("The last name of the contact.")
                .required(true),
            string(FIRST_NAME)
                .label("First Name")
                .description("The first name of the contact.")
                .required(false),
            string(EMAIL)
                .label("Email")
                .description("The primary email address of the contact.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The created contact reference.")
                            .properties(
                                string("type")
                                    .description("The type of the created resource."),
                                string("id")
                                    .description("The id of the created contact.")))))
        .help("", "https://docs.bytechef.io/reference/components/teamleader_v1#create-contact")
        .perform(TeamleaderCreateContactAction::perform);

    private TeamleaderCreateContactAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(LAST_NAME, inputParameters.getRequiredString(LAST_NAME));

        String firstName = inputParameters.getString(FIRST_NAME);

        if (firstName != null) {
            body.put(FIRST_NAME, firstName);
        }

        String email = inputParameters.getString(EMAIL);

        if (email != null) {
            body.put("emails", List.of(Map.of("type", "primary", EMAIL, email)));
        }

        return context.http(http -> http.post("/contacts.add"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
