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

package com.bytechef.component.onepagecrm.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.onepagecrm.constant.OnePageCrmConstants.COMPANY_NAME;
import static com.bytechef.component.onepagecrm.constant.OnePageCrmConstants.FIRST_NAME;
import static com.bytechef.component.onepagecrm.constant.OnePageCrmConstants.LAST_NAME;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class OnePageCrmCreateContactAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createContact")
        .title("Create Contact")
        .description("Creates a new contact in OnePageCRM.")
        .properties(
            string(LAST_NAME)
                .label("Last Name")
                .description("The last name of the contact.")
                .required(true),
            string(FIRST_NAME)
                .label("First Name")
                .description("The first name of the contact.")
                .required(false),
            string(COMPANY_NAME)
                .label("Company Name")
                .description("The company name of the contact.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("status")
                            .description("The status of the request."),
                        string("message")
                            .description("The message of the request."),
                        object("data")
                            .description("The response data.")
                            .properties(
                                object("contact")
                                    .description("The created contact.")
                                    .properties(
                                        string("id")
                                            .description("The id of the created contact."),
                                        string("first_name")
                                            .description("The first name of the created contact."),
                                        string("last_name")
                                            .description("The last name of the created contact."),
                                        string("company_name")
                                            .description("The company name of the created contact."))))))
        .help("", "https://docs.bytechef.io/reference/components/onepagecrm_v1#create-contact")
        .perform(OnePageCrmCreateContactAction::perform);

    private OnePageCrmCreateContactAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(LAST_NAME, inputParameters.getRequiredString(LAST_NAME));

        String firstName = inputParameters.getString(FIRST_NAME);

        if (firstName != null) {
            body.put(FIRST_NAME, firstName);
        }

        String companyName = inputParameters.getString(COMPANY_NAME);

        if (companyName != null) {
            body.put(COMPANY_NAME, companyName);
        }

        return context.http(http -> http.post("/contacts.json"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
