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
import static com.bytechef.component.home.assistant.constant.HomeAssistantConstants.DOMAIN;
import static com.bytechef.component.home.assistant.constant.HomeAssistantConstants.ENTITY_ID;
import static com.bytechef.component.home.assistant.constant.HomeAssistantConstants.SERVICE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class HomeAssistantCallServiceAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("callService")
        .title("Call Service")
        .description("Calls a Home Assistant service for an entity (e.g. turn a light on).")
        .properties(
            string(DOMAIN)
                .label("Domain")
                .description("The domain of the service (e.g. light, switch).")
                .required(true),
            string(SERVICE)
                .label("Service")
                .description("The name of the service (e.g. turn_on, turn_off).")
                .required(true),
            string(ENTITY_ID)
                .label("Entity Id")
                .description("The id of the entity to call the service for (e.g. light.living_room).")
                .required(true))
        .output(
            outputSchema(
                array()
                    .description("The states that changed while the service was executed.")
                    .items(
                        object()
                            .properties(
                                string("entity_id")
                                    .description("The id of the entity."),
                                string("state")
                                    .description("The state of the entity.")))))
        .help("", "https://docs.bytechef.io/reference/components/homeAssistant_v1#call-service")
        .perform(HomeAssistantCallServiceAction::perform);

    private HomeAssistantCallServiceAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.post(
                "/services/%s/%s".formatted(
                    inputParameters.getRequiredString(DOMAIN), inputParameters.getRequiredString(SERVICE))))
            .body(Body.of("entity_id", inputParameters.getRequiredString(ENTITY_ID)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
