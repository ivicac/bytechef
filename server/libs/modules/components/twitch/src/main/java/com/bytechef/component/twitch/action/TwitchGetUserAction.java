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

package com.bytechef.component.twitch.action;

import static com.bytechef.component.definition.Authorization.CLIENT_ID;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class TwitchGetUserAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getUser")
        .title("Get User")
        .description("Returns information about the authenticated user or a user specified by login name.")
        .properties(
            string("login")
                .label("Login")
                .description("The login name of the user to look up. Leave empty for the authenticated user.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("data")
                            .items(
                                object()
                                    .properties(
                                        string("id"),
                                        string("login"),
                                        string("display_name"),
                                        string("type"),
                                        string("description"))))))
        .perform(TwitchGetUserAction::perform);

    private TwitchGetUserAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String login = inputParameters.getString("login");

        return context.http(http -> http.get(login == null ? "/users" : "/users?login=" + login))
            .header("Client-Id", connectionParameters.getRequiredString(CLIENT_ID))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
