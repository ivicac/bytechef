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

package com.bytechef.component.chatwoot.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
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
public class ChatwootListConversationsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listConversations")
        .title("List Conversations")
        .description("Returns the conversations in a Chatwoot account.")
        .properties(
            integer("accountId")
                .label("Account ID")
                .description("The ID of the Chatwoot account.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .properties(
                                array("payload")
                                    .items(
                                        object()
                                            .properties(
                                                integer("id"),
                                                string("status")))))))
        .perform(ChatwootListConversationsAction::perform);

    private ChatwootListConversationsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get(
                "/api/v1/accounts/%s/conversations".formatted(inputParameters.getRequiredInteger("accountId"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
