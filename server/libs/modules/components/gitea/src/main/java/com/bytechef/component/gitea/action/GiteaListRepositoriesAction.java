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

package com.bytechef.component.gitea.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.gitea.constant.GiteaConstants.LIMIT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class GiteaListRepositoriesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listRepositories")
        .title("List Repositories")
        .description("Returns the repositories of the authenticated user.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of repositories to return per page.")
                .required(false))
        .output(
            outputSchema(
                array()
                    .description("The repositories of the user.")
                    .items(
                        object()
                            .properties(
                                integer("id")
                                    .description("The id of the repository."),
                                string("name")
                                    .description("The name of the repository."),
                                string("full_name")
                                    .description("The full name of the repository."),
                                string("html_url")
                                    .description("The URL of the repository.")))))
        .help("", "https://docs.bytechef.io/reference/components/gitea_v1#list-repositories")
        .perform(GiteaListRepositoriesAction::perform);

    private GiteaListRepositoriesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/user/repos"))
            .queryParameters(LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
