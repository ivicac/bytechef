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

package com.bytechef.component.constant.contact.action;

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
public class ConstantContactListContactListsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listContactLists")
        .title("List Contact Lists")
        .description("Returns the contact lists in your Constant Contact account.")
        .properties(
            integer("limit")
                .label("Limit")
                .description("The maximum number of contact lists to return.")
                .defaultValue(50)
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("lists")
                            .items(
                                object()
                                    .properties(
                                        string("list_id"),
                                        string("name"),
                                        string("created_at"))))))
        .perform(ConstantContactListContactListsAction::perform);

    private ConstantContactListContactListsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/contact_lists"))
            .queryParameters("limit", inputParameters.getInteger("limit", 50))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
