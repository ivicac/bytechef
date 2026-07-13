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

package com.bytechef.component.zoho.bigin.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.zoho.bigin.constant.ZohoBiginConstants.FIELDS;
import static com.bytechef.component.zoho.bigin.constant.ZohoBiginConstants.PER_PAGE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class ZohoBiginListContactsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listContacts")
        .title("List Contacts")
        .description("Returns the contacts of the Bigin account.")
        .properties(
            string(FIELDS)
                .label("Fields")
                .description("Comma-separated names of the fields to return (e.g. First_Name,Last_Name,Email).")
                .defaultValue("First_Name,Last_Name,Email")
                .required(true),
            integer(PER_PAGE)
                .label("Per Page")
                .description("The maximum number of contacts to return per page.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("data")
                            .description("The contacts of the account.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the contact."),
                                        string("First_Name")
                                            .description("The first name of the contact."),
                                        string("Last_Name")
                                            .description("The last name of the contact."),
                                        string("Email")
                                            .description("The email address of the contact."))))))
        .help("", "https://docs.bytechef.io/reference/components/zoho-bigin_v1#list-contacts")
        .perform(ZohoBiginListContactsAction::perform);

    private ZohoBiginListContactsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/Contacts"))
            .queryParameters(
                FIELDS, inputParameters.getRequiredString(FIELDS),
                PER_PAGE, inputParameters.getInteger(PER_PAGE))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
