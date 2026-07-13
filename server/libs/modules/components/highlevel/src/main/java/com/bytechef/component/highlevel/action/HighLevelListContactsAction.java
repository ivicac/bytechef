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

package com.bytechef.component.highlevel.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.highlevel.constant.HighLevelConstants.LIMIT;
import static com.bytechef.component.highlevel.constant.HighLevelConstants.LOCATION_ID;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class HighLevelListContactsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listContacts")
        .title("List Contacts")
        .description("Returns the contacts of the location.")
        .properties(
            string(LOCATION_ID)
                .label("Location Id")
                .description("The id of the HighLevel location (sub-account).")
                .required(true),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of contacts to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("contacts")
                            .description("The contacts of the location.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the contact."),
                                        string("firstName")
                                            .description("The first name of the contact."),
                                        string("lastName")
                                            .description("The last name of the contact."),
                                        string("email")
                                            .description("The email address of the contact."))))))
        .help("", "https://docs.bytechef.io/reference/components/highLevel_v1#list-contacts")
        .perform(HighLevelListContactsAction::perform);

    private HighLevelListContactsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/contacts/"))
            .queryParameters(
                LOCATION_ID, inputParameters.getRequiredString(LOCATION_ID),
                LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
