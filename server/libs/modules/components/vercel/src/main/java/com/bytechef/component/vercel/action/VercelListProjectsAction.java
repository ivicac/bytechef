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

package com.bytechef.component.vercel.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.number;
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
public class VercelListProjectsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listProjects")
        .title("List Projects")
        .description("Lists the projects of the authenticated user or team.")
        .output(
            outputSchema(
                object()
                    .properties(
                        array("projects")
                            .description("The projects of the account.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the project."),
                                        string("name")
                                            .description("The name of the project."),
                                        string("framework")
                                            .description("The framework of the project."),
                                        number("createdAt")
                                            .description("The creation timestamp of the project."))))))
        .help("", "https://docs.bytechef.io/reference/components/vercel_v1#list-projects")
        .perform(VercelListProjectsAction::perform);

    private VercelListProjectsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/v10/projects"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
