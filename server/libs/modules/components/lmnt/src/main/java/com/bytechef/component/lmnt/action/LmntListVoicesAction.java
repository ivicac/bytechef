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

package com.bytechef.component.lmnt.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.lmnt.constant.LmntConstants.OWNER;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class LmntListVoicesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listVoices")
        .title("List Voices")
        .description("Returns a list of voices available for speech synthesis.")
        .properties(
            string(OWNER)
                .label("Owner")
                .description("Which owner's voices to return.")
                .options(option("All", "all"), option("System", "system"), option("Me", "me"))
                .required(false))
        .output(
            outputSchema(
                array()
                    .description("The voices available for speech synthesis.")
                    .items(
                        object()
                            .properties(
                                string("id")
                                    .description("The id of the voice."),
                                string("name")
                                    .description("The name of the voice."),
                                string("owner")
                                    .description("The owner of the voice."),
                                string("state")
                                    .description("The state of the voice.")))))
        .help("", "https://docs.bytechef.io/reference/components/lmnt_v1#list-voices")
        .perform(LmntListVoicesAction::perform);

    private LmntListVoicesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/v1/ai/voice/list"))
            .queryParameters(OWNER, inputParameters.getString(OWNER))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
