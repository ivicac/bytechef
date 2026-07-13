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

package com.bytechef.component.bubble.action;

import static com.bytechef.component.bubble.constant.BubbleConstants.DATA;
import static com.bytechef.component.bubble.constant.BubbleConstants.TYPE_NAME;
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
public class BubbleCreateThingAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createThing")
        .title("Create Thing")
        .description("Creates a new thing of the specified data type in your Bubble app database.")
        .properties(
            string(TYPE_NAME)
                .label("Data Type")
                .description("The name of the data type of the thing that will be created (e.g. user, product).")
                .required(true),
            object(DATA)
                .label("Data")
                .description("The field values of the thing that will be created.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("status")
                            .description("The status of the request."),
                        string("id")
                            .description("The id of the created thing."))))
        .help("", "https://docs.bytechef.io/reference/components/bubble_v1#create-thing")
        .perform(BubbleCreateThingAction::perform);

    private BubbleCreateThingAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/obj/%s".formatted(inputParameters.getRequiredString(TYPE_NAME))))
            .body(Body.of(inputParameters.getRequiredMap(DATA, Object.class)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
