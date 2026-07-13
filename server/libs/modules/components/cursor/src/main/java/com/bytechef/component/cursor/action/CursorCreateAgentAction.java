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

package com.bytechef.component.cursor.action;

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
import com.bytechef.component.definition.Property.ControlType;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class CursorCreateAgentAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createAgent")
        .title("Create Agent")
        .description("Launches a background coding agent on a repository.")
        .properties(
            string("prompt")
                .label("Prompt")
                .description("The task for the agent to perform.")
                .controlType(ControlType.TEXT_AREA)
                .required(true),
            string("repository")
                .label("Repository")
                .description("The URL of the GitHub repository the agent works on.")
                .required(true),
            string("ref")
                .label("Ref")
                .description("The branch, tag, or commit to start from.")
                .required(false),
            string("model")
                .label("Model")
                .description("The model the agent uses.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id"),
                        string("name"),
                        string("status"))))
        .perform(CursorCreateAgentAction::perform);

    private CursorCreateAgentAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> source = new HashMap<>();

        source.put("repository", inputParameters.getRequiredString("repository"));

        if (inputParameters.getString("ref") != null) {
            source.put("ref", inputParameters.getString("ref"));
        }

        Map<String, Object> body = new HashMap<>();

        body.put("prompt", Map.of("text", inputParameters.getRequiredString("prompt")));
        body.put("source", source);

        if (inputParameters.getString("model") != null) {
            body.put("model", inputParameters.getString("model"));
        }

        return context.http(http -> http.post("/v0/agents"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
