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

package com.bytechef.component.front.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.front.constant.FrontConstants.LIMIT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class FrontListConversationsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listConversations")
        .title("List Conversations")
        .description("Returns the conversations of the Front company.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of conversations to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("_results")
                            .description("The conversations of the company.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the conversation."),
                                        string("subject")
                                            .description("The subject of the conversation."),
                                        string("status")
                                            .description("The status of the conversation."))))))
        .help("", "https://docs.bytechef.io/reference/components/front_v1#list-conversations")
        .perform(FrontListConversationsAction::perform);

    private FrontListConversationsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/conversations"))
            .queryParameters(LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
