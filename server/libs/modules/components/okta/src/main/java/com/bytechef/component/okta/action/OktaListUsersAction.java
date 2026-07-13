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

package com.bytechef.component.okta.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.okta.constant.OktaConstants.LIMIT;
import static com.bytechef.component.okta.constant.OktaConstants.Q;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class OktaListUsersAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listUsers")
        .title("List Users")
        .description("Lists the users of the Okta organization.")
        .properties(
            string(Q)
                .label("Query")
                .description("Only return users whose first name, last name or email starts with this value.")
                .required(false),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of users to return.")
                .required(false))
        .output(
            outputSchema(
                array()
                    .description("The users of the organization.")
                    .items(
                        object()
                            .properties(
                                string("id")
                                    .description("The id of the user."),
                                string("status")
                                    .description("The status of the user."),
                                string("created")
                                    .description("The date and time the user was created."),
                                object("profile")
                                    .description("The profile of the user.")
                                    .properties(
                                        string("firstName")
                                            .description("The first name of the user."),
                                        string("lastName")
                                            .description("The last name of the user."),
                                        string("email")
                                            .description("The email address of the user."))))))
        .help("", "https://docs.bytechef.io/reference/components/okta_v1#list-users")
        .perform(OktaListUsersAction::perform);

    private OktaListUsersAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/users"))
            .queryParameters(
                Q, inputParameters.getString(Q),
                LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
