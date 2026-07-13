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

package com.bytechef.component.aircall.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class AircallCreateContactAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createContact")
        .title("Create Contact")
        .description("Creates a new contact in the Aircall account.")
        .properties(
            string("firstName")
                .label("First Name")
                .description("The first name of the contact.")
                .required(true),
            string("lastName")
                .label("Last Name")
                .description("The last name of the contact.")
                .required(false),
            string("email")
                .label("Email")
                .description("The email address of the contact.")
                .required(false))
        .output(outputSchema(object()))
        .perform(AircallCreateContactAction::perform);

    private AircallCreateContactAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put("first_name", inputParameters.getRequiredString("firstName"));

        String lastName = inputParameters.getString("lastName");

        if (lastName != null) {
            body.put("last_name", lastName);
        }

        String email = inputParameters.getString("email");

        if (email != null) {
            body.put("emails", List.of(Map.of("label", "Work", "value", email)));
        }

        return context.http(http -> http.post("/contacts"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
