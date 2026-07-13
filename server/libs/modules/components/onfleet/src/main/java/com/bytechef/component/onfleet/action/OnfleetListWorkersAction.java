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

package com.bytechef.component.onfleet.action;

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
public class OnfleetListWorkersAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listWorkers")
        .title("List Workers")
        .description("Returns a list of workers in the organization.")
        .output(
            outputSchema(
                array()
                    .items(
                        object()
                            .properties(
                                string("id")
                                    .description("The id of the worker."),
                                string("name")
                                    .description("The name of the worker."),
                                string("phone")
                                    .description("The phone number of the worker."),
                                integer("onDuty")
                                    .description("Whether the worker is on duty.")))))
        .help("", "https://docs.bytechef.io/reference/components/onfleet_v1#list-workers")
        .perform(OnfleetListWorkersAction::perform);

    private OnfleetListWorkersAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get("/workers"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
