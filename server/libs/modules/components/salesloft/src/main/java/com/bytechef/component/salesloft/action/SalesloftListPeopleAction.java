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

package com.bytechef.component.salesloft.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.salesloft.constant.SalesloftConstants.PER_PAGE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class SalesloftListPeopleAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listPeople")
        .title("List People")
        .description("Returns the people of the Salesloft team.")
        .properties(
            integer(PER_PAGE)
                .label("Per Page")
                .description("The maximum number of people to return per page.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("data")
                            .description("The people of the team.")
                            .items(
                                object()
                                    .properties(
                                        integer("id")
                                            .description("The id of the person."),
                                        string("first_name")
                                            .description("The first name of the person."),
                                        string("last_name")
                                            .description("The last name of the person."),
                                        string("email_address")
                                            .description("The email address of the person."))))))
        .help("", "https://docs.bytechef.io/reference/components/salesloft_v1#list-people")
        .perform(SalesloftListPeopleAction::perform);

    private SalesloftListPeopleAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/people.json"))
            .queryParameters("per_page", inputParameters.getInteger(PER_PAGE))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
