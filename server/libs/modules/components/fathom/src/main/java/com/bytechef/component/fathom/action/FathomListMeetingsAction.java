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

package com.bytechef.component.fathom.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.fathom.constant.FathomConstants.LIMIT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class FathomListMeetingsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listMeetings")
        .title("List Meetings")
        .description("Returns a list of recorded meetings.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of meetings to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("items")
                            .description("The recorded meetings.")
                            .items(
                                object()
                                    .properties(
                                        string("title")
                                            .description("The title of the meeting."),
                                        string("url")
                                            .description("The URL of the meeting recording."),
                                        string("created_at")
                                            .description("The date the meeting was recorded."))),
                        string("next_cursor")
                            .description("The cursor of the next result page."))))
        .help("", "https://docs.bytechef.io/reference/components/fathom_v1#list-meetings")
        .perform(FathomListMeetingsAction::perform);

    private FathomListMeetingsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get("/meetings"))
            .queryParameters(LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
