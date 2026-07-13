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

package com.bytechef.component.neon.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.neon.constant.NeonConstants.NAME;
import static com.bytechef.component.neon.constant.NeonConstants.PG_VERSION;
import static com.bytechef.component.neon.constant.NeonConstants.PROJECT;
import static com.bytechef.component.neon.constant.NeonConstants.REGION_ID;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class NeonCreateProjectAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createProject")
        .title("Create Project")
        .description("Creates a new Neon project.")
        .properties(
            string(NAME)
                .label("Name")
                .description("The name of the project.")
                .required(true),
            string(REGION_ID)
                .label("Region ID")
                .description("The region where the project will be created (e.g. aws-us-east-1).")
                .required(false),
            integer(PG_VERSION)
                .label("Postgres Version")
                .description("The major Postgres version of the project.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object(PROJECT)
                            .description("The created project.")
                            .properties(
                                string("id")
                                    .description("The id of the created project."),
                                string("name")
                                    .description("The name of the created project."),
                                string("region_id")
                                    .description("The region of the created project."),
                                integer("pg_version")
                                    .description("The Postgres version of the created project."),
                                string("created_at")
                                    .description("The date and time the project was created.")))))
        .help("", "https://docs.bytechef.io/reference/components/neon_v1#create-project")
        .perform(NeonCreateProjectAction::perform);

    private NeonCreateProjectAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> project = new HashMap<>();

        project.put(NAME, inputParameters.getRequiredString(NAME));

        String regionId = inputParameters.getString(REGION_ID);

        if (regionId != null) {
            project.put(REGION_ID, regionId);
        }

        Integer pgVersion = inputParameters.getInteger(PG_VERSION);

        if (pgVersion != null) {
            project.put(PG_VERSION, pgVersion);
        }

        return context.http(http -> http.post("/projects"))
            .body(Body.of(Map.of(PROJECT, project)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
