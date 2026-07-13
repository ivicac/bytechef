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

package com.bytechef.component.home.assistant.action;

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
public class HomeAssistantGetStatesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getStates")
        .title("Get States")
        .description("Returns the states of all entities of the Home Assistant instance.")
        .output(
            outputSchema(
                array()
                    .description("The states of all entities.")
                    .items(
                        object()
                            .properties(
                                string("entity_id")
                                    .description("The id of the entity."),
                                string("state")
                                    .description("The state of the entity."),
                                string("last_changed")
                                    .description("The date and time when the state last changed.")))))
        .help("", "https://docs.bytechef.io/reference/components/homeAssistant_v1#get-states")
        .perform(HomeAssistantGetStatesAction::perform);

    private HomeAssistantGetStatesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/states"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
