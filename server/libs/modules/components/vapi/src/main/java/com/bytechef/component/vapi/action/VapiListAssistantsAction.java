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

package com.bytechef.component.vapi.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.vapi.constant.VapiConstants.LIMIT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class VapiListAssistantsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listAssistants")
        .title("List Assistants")
        .description("Returns the assistants of the Vapi organization.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of assistants to return.")
                .required(false))
        .output(
            outputSchema(
                array()
                    .description("The assistants of the organization.")
                    .items(
                        object()
                            .properties(
                                string("id")
                                    .description("The id of the assistant."),
                                string("name")
                                    .description("The name of the assistant."),
                                string("createdAt")
                                    .description("The date and time when the assistant was created.")))))
        .help("", "https://docs.bytechef.io/reference/components/vapi_v1#list-assistants")
        .perform(VapiListAssistantsAction::perform);

    private VapiListAssistantsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/assistant"))
            .queryParameters(LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
