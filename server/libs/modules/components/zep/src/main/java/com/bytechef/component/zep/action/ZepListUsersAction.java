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

package com.bytechef.component.zep.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.zep.constant.ZepConstants.PAGE_SIZE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class ZepListUsersAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listUsers")
        .title("List Users")
        .description("Returns the users of the Zep project.")
        .properties(
            integer(PAGE_SIZE)
                .label("Page Size")
                .description("The maximum number of users to return per page.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("users")
                            .description("The users of the project.")
                            .items(
                                object()
                                    .properties(
                                        string("user_id")
                                            .description("The id of the user."),
                                        string("email")
                                            .description("The email address of the user."))))))
        .help("", "https://docs.bytechef.io/reference/components/zep_v1#list-users")
        .perform(ZepListUsersAction::perform);

    private ZepListUsersAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/users-ordered"))
            .queryParameters(PAGE_SIZE, inputParameters.getInteger(PAGE_SIZE))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
