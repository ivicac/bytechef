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

package com.bytechef.component.browserbase.action;

import static com.bytechef.component.browserbase.constant.BrowserbaseConstants.PROJECT_ID;
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
public class BrowserbaseCreateSessionAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createSession")
        .title("Create Session")
        .description("Creates a new headless browser session.")
        .properties(
            string(PROJECT_ID)
                .label("Project ID")
                .description("The id of the Browserbase project the session belongs to.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the created session."),
                        string("projectId")
                            .description("The id of the project the session belongs to."),
                        string("status")
                            .description("The status of the created session."),
                        string("connectUrl")
                            .description("The WebSocket URL used to connect to the session."),
                        string("createdAt")
                            .description("The date and time the session was created."))))
        .help("", "https://docs.bytechef.io/reference/components/browserbase_v1#create-session")
        .perform(BrowserbaseCreateSessionAction::perform);

    private BrowserbaseCreateSessionAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context.http(http -> http.post("/sessions"))
            .body(Body.of(Map.of(PROJECT_ID, inputParameters.getRequiredString(PROJECT_ID))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
