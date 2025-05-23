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

package com.bytechef.component.zendesk.sell.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.date;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.zendesk.sell.constant.ZendeskSellConstants.CONTENT;
import static com.bytechef.component.zendesk.sell.constant.ZendeskSellConstants.DATA;
import static com.bytechef.component.zendesk.sell.constant.ZendeskSellConstants.DUE_DATE;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context.Http;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;

/**
 * @author Monika Domiter
 */
public class ZendeskSellCreateTaskAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createTask")
        .title("Create Task")
        .description("Creates new task.")
        .properties(
            string(CONTENT)
                .label("Task Name")
                .required(true),
            date(DUE_DATE)
                .label("Due Date")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object(DATA)
                            .properties(
                                integer("id")
                                    .description("The ID of the task."),
                                string(CONTENT)
                                    .description("Name of the task."),
                                date(DUE_DATE)
                                    .description("Due date of the task.")),
                        object("meta")
                            .properties(
                                string("type")))))
        .perform(ZendeskSellCreateTaskAction::perform);

    private ZendeskSellCreateTaskAction() {
    }

    public static Object perform(
        Parameters inputParameters, Parameters connectionParameters, ActionContext actionContext) {

        return actionContext.http(http -> http.post("/tasks"))
            .body(
                Http.Body.of(
                    "data",
                    new Object[] {
                        CONTENT, inputParameters.getRequiredString(CONTENT),
                        DUE_DATE, inputParameters.getDate(DUE_DATE)
                    }))
            .configuration(Http.responseType(Http.ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
