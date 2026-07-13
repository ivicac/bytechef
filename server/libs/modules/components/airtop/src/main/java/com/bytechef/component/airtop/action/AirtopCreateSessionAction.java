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

package com.bytechef.component.airtop.action;

import static com.bytechef.component.airtop.constant.AirtopConstants.CONFIGURATION;
import static com.bytechef.component.airtop.constant.AirtopConstants.TIMEOUT_MINUTES;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
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
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class AirtopCreateSessionAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createSession")
        .title("Create Session")
        .description("Creates a new cloud browser session.")
        .properties(
            integer(TIMEOUT_MINUTES)
                .label("Timeout Minutes")
                .description("The number of idle minutes after which the session automatically terminates.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The created session.")
                            .properties(
                                string("id")
                                    .description("The id of the created session."),
                                string("status")
                                    .description("The status of the created session."),
                                string("cdpUrl")
                                    .description("The Chrome DevTools Protocol URL of the session.")))))
        .help("", "https://docs.bytechef.io/reference/components/airtop_v1#create-session")
        .perform(AirtopCreateSessionAction::perform);

    private AirtopCreateSessionAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        Integer timeoutMinutes = inputParameters.getInteger(TIMEOUT_MINUTES);

        if (timeoutMinutes != null) {
            body.put(CONFIGURATION, Map.of(TIMEOUT_MINUTES, timeoutMinutes));
        }

        return context.http(http -> http.post("/sessions"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
