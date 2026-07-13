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

package com.bytechef.component.rocketlane.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.date;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.rocketlane.constant.RocketlaneConstants.PROJECT_NAME;
import static com.bytechef.component.rocketlane.constant.RocketlaneConstants.START_DATE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class RocketlaneCreateProjectAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createProject")
        .title("Create Project")
        .description("Creates a new project in Rocketlane.")
        .properties(
            string(PROJECT_NAME)
                .label("Project Name")
                .description("The name of the project.")
                .required(true),
            date(START_DATE)
                .label("Start Date")
                .description("The start date of the project.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("projectId")
                            .description("The id of the created project."),
                        string("projectName")
                            .description("The name of the created project."),
                        string("startDate")
                            .description("The start date of the created project."),
                        string("status")
                            .description("The status of the created project."))))
        .help("", "https://docs.bytechef.io/reference/components/rocketlane_v1#create-project")
        .perform(RocketlaneCreateProjectAction::perform);

    private RocketlaneCreateProjectAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(PROJECT_NAME, inputParameters.getRequiredString(PROJECT_NAME));

        LocalDate startDate = inputParameters.getLocalDate(START_DATE);

        if (startDate != null) {
            body.put(START_DATE, startDate.toString());
        }

        return context.http(http -> http.post("/projects"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
