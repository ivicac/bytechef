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

package com.bytechef.component.nextcloud.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.nextcloud.constant.NextcloudConstants.SEARCH;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class NextcloudListUsersAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listUsers")
        .title("List Users")
        .description("Returns the users of the Nextcloud instance. Requires admin permissions.")
        .properties(
            string(SEARCH)
                .label("Search")
                .description("The text to search usernames by.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("ocs")
                            .description("The OCS response.")
                            .properties(
                                object("data")
                                    .description("The response data.")
                                    .properties(
                                        array("users")
                                            .description("The usernames of the users.")
                                            .items(string()))))))
        .help("", "https://docs.bytechef.io/reference/components/nextcloud_v1#list-users")
        .perform(NextcloudListUsersAction::perform);

    private NextcloudListUsersAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/ocs/v1.php/cloud/users"))
            .header("OCS-APIRequest", "true")
            .queryParameters(
                "format", "json",
                SEARCH, inputParameters.getString(SEARCH))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
