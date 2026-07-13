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

package com.bytechef.component.launchdarkly.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.launchdarkly.constant.LaunchDarklyConstants.LIMIT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class LaunchDarklyListProjectsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listProjects")
        .title("List Projects")
        .description("Returns the projects of the LaunchDarkly account.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of projects to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("items")
                            .description("The projects of the account.")
                            .items(
                                object()
                                    .properties(
                                        string("key")
                                            .description("The key of the project."),
                                        string("name")
                                            .description("The name of the project."))))))
        .help("", "https://docs.bytechef.io/reference/components/launchDarkly_v1#list-projects")
        .perform(LaunchDarklyListProjectsAction::perform);

    private LaunchDarklyListProjectsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/projects"))
            .queryParameters(LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
