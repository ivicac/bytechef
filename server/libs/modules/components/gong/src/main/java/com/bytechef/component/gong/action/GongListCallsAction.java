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

package com.bytechef.component.gong.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.gong.constant.GongConstants.FROM_DATE_TIME;
import static com.bytechef.component.gong.constant.GongConstants.TO_DATE_TIME;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class GongListCallsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listCalls")
        .title("List Calls")
        .description("Returns a list of calls that took place during the specified period.")
        .properties(
            string(FROM_DATE_TIME)
                .label("From Date Time")
                .description("The date and time from which to list calls, in ISO-8601 format.")
                .required(true),
            string(TO_DATE_TIME)
                .label("To Date Time")
                .description("The date and time until which to list calls, in ISO-8601 format.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("calls")
                            .description("The calls that took place during the specified period.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the call."),
                                        string("title")
                                            .description("The title of the call."),
                                        string("started")
                                            .description("The date and time the call started."),
                                        string("url")
                                            .description("The URL of the call in Gong."))))))
        .help("", "https://docs.bytechef.io/reference/components/gong_v1#list-calls")
        .perform(GongListCallsAction::perform);

    private GongListCallsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get("/calls"))
            .queryParameters(
                FROM_DATE_TIME, inputParameters.getRequiredString(FROM_DATE_TIME),
                TO_DATE_TIME, inputParameters.getRequiredString(TO_DATE_TIME))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
