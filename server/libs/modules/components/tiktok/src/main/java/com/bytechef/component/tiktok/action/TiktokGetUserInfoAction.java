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

package com.bytechef.component.tiktok.action;

import static com.bytechef.component.definition.ComponentDsl.action;
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
public class TiktokGetUserInfoAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getUserInfo")
        .title("Get User Info")
        .description("Returns profile information of the authenticated TikTok user.")
        .properties(
            string("fields")
                .label("Fields")
                .description("Comma-separated list of user fields to return.")
                .defaultValue("open_id,union_id,avatar_url,display_name")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .properties(
                                object("user")
                                    .properties(
                                        string("open_id"),
                                        string("display_name"),
                                        string("avatar_url"))))))
        .perform(TiktokGetUserInfoAction::perform);

    private TiktokGetUserInfoAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/user/info/"))
            .queryParameters("fields", inputParameters.getRequiredString("fields"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
