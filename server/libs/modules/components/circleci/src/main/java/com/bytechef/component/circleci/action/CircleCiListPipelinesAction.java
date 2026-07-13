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

package com.bytechef.component.circleci.action;

import static com.bytechef.component.circleci.constant.CircleCiConstants.ORG_SLUG;
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
public class CircleCiListPipelinesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listPipelines")
        .title("List Pipelines")
        .description("Returns the recent pipelines of the organization.")
        .properties(
            string(ORG_SLUG)
                .label("Organization Slug")
                .description("The slug of the organization (e.g. gh/my-org or circleci/my-org).")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("items")
                            .description("The pipelines of the organization.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the pipeline."),
                                        string("state")
                                            .description("The state of the pipeline."),
                                        string("project_slug")
                                            .description("The slug of the project."))))))
        .help("", "https://docs.bytechef.io/reference/components/circleCi_v1#list-pipelines")
        .perform(CircleCiListPipelinesAction::perform);

    private CircleCiListPipelinesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/pipeline"))
            .queryParameters("org-slug", inputParameters.getRequiredString(ORG_SLUG))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
