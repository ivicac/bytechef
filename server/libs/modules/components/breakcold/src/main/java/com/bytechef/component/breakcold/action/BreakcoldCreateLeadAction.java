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

package com.bytechef.component.breakcold.action;

import static com.bytechef.component.breakcold.constant.BreakcoldConstants.COMPANY;
import static com.bytechef.component.breakcold.constant.BreakcoldConstants.EMAIL;
import static com.bytechef.component.breakcold.constant.BreakcoldConstants.FIRST_NAME;
import static com.bytechef.component.breakcold.constant.BreakcoldConstants.LAST_NAME;
import static com.bytechef.component.breakcold.constant.BreakcoldConstants.LIST_ID;
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

/**
 * @author Ivica Cardic
 */
public class BreakcoldCreateLeadAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createLead")
        .title("Create Lead")
        .description("Creates a new lead in the workspace.")
        .properties(
            string(FIRST_NAME)
                .label("First Name")
                .description("The first name of the lead.")
                .required(false),
            string(LAST_NAME)
                .label("Last Name")
                .description("The last name of the lead.")
                .required(false),
            string(EMAIL)
                .label("Email")
                .description("The email address of the lead.")
                .required(false),
            string(COMPANY)
                .label("Company")
                .description("The company of the lead.")
                .required(false),
            string(LIST_ID)
                .label("List ID")
                .description("The id of the list to add the lead to.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the lead."),
                        string(FIRST_NAME)
                            .description("The first name of the lead."),
                        string(LAST_NAME)
                            .description("The last name of the lead."),
                        string(EMAIL)
                            .description("The email address of the lead."),
                        string(COMPANY)
                            .description("The company of the lead."))))
        .help("", "https://docs.bytechef.io/reference/components/breakcold_v1#create-lead")
        .perform(BreakcoldCreateLeadAction::perform);

    private BreakcoldCreateLeadAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/leads"))
            .body(
                Body.of(
                    FIRST_NAME, inputParameters.getString(FIRST_NAME),
                    LAST_NAME, inputParameters.getString(LAST_NAME),
                    EMAIL, inputParameters.getString(EMAIL),
                    COMPANY, inputParameters.getString(COMPANY),
                    LIST_ID, inputParameters.getString(LIST_ID)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
