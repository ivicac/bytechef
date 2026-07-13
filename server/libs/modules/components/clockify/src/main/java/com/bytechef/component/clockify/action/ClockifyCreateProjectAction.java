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

package com.bytechef.component.clockify.action;

import static com.bytechef.component.clockify.constant.ClockifyConstants.NAME;
import static com.bytechef.component.clockify.constant.ClockifyConstants.WORKSPACE_ID;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class ClockifyCreateProjectAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createProject")
        .title("Create Project")
        .description("Creates a new project in the specified workspace.")
        .properties(
            string(WORKSPACE_ID)
                .label("Workspace ID")
                .description("The id of the workspace where the project will be created.")
                .required(true),
            string(NAME)
                .label("Name")
                .description("The name of the project.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the created project."),
                        string("name")
                            .description("The name of the created project."),
                        string("workspaceId")
                            .description("The id of the workspace the project belongs to."),
                        string("color")
                            .description("The color of the created project."))))
        .help("", "https://docs.bytechef.io/reference/components/clockify_v1#create-project")
        .perform(ClockifyCreateProjectAction::perform);

    private ClockifyCreateProjectAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post(
                "/workspaces/%s/projects".formatted(inputParameters.getRequiredString(WORKSPACE_ID))))
            .body(Body.of(Map.of(NAME, inputParameters.getRequiredString(NAME))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
