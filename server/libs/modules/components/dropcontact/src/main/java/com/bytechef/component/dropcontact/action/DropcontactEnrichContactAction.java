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

package com.bytechef.component.dropcontact.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.dropcontact.constant.DropcontactConstants.COMPANY;
import static com.bytechef.component.dropcontact.constant.DropcontactConstants.EMAIL;
import static com.bytechef.component.dropcontact.constant.DropcontactConstants.FIRST_NAME;
import static com.bytechef.component.dropcontact.constant.DropcontactConstants.LAST_NAME;

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
public class DropcontactEnrichContactAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("enrichContact")
        .title("Enrich Contact")
        .description("Submits a contact for enrichment and returns the request id.")
        .properties(
            string(EMAIL)
                .label("Email")
                .description("The email address of the contact.")
                .required(true),
            string(FIRST_NAME)
                .label("First Name")
                .description("The first name of the contact.")
                .required(false),
            string(LAST_NAME)
                .label("Last Name")
                .description("The last name of the contact.")
                .required(false),
            string(COMPANY)
                .label("Company")
                .description("The company name of the contact.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        bool("success")
                            .description("Whether the enrichment request was accepted."),
                        string("request_id")
                            .description("The id used to fetch the enrichment result."))))
        .help("", "https://docs.bytechef.io/reference/components/dropcontact_v1#enrich-contact")
        .perform(DropcontactEnrichContactAction::perform);

    private DropcontactEnrichContactAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> data = new HashMap<>();

        data.put(EMAIL, inputParameters.getRequiredString(EMAIL));

        String firstName = inputParameters.getString(FIRST_NAME);

        if (firstName != null) {
            data.put(FIRST_NAME, firstName);
        }

        String lastName = inputParameters.getString(LAST_NAME);

        if (lastName != null) {
            data.put(LAST_NAME, lastName);
        }

        String company = inputParameters.getString(COMPANY);

        if (company != null) {
            data.put(COMPANY, company);
        }

        return context
            .http(http -> http.post("/batch"))
            .body(Body.of(Map.of("data", List.of(data))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
