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

package com.bytechef.component.fireflies.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.fireflies.constant.FirefliesConstants.LIMIT;
import static com.bytechef.component.fireflies.constant.FirefliesConstants.QUERY;
import static com.bytechef.component.fireflies.constant.FirefliesConstants.VARIABLES;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class FirefliesGetTranscriptsAction {

    private static final String TRANSCRIPTS_QUERY = """
        query Transcripts($limit: Int) {
          transcripts(limit: $limit) {
            id
            title
            date
            duration
            transcript_url
          }
        }""";

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getTranscripts")
        .title("Get Transcripts")
        .description("Retrieves the most recent meeting transcripts.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of transcripts to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The response data.")
                            .properties(
                                array("transcripts")
                                    .description("The meeting transcripts.")
                                    .items(
                                        object()
                                            .properties(
                                                string("id")
                                                    .description("The id of the transcript."),
                                                string("title")
                                                    .description("The title of the meeting."),
                                                number("date")
                                                    .description("The date of the meeting as a unix timestamp."),
                                                number("duration")
                                                    .description("The duration of the meeting in minutes."),
                                                string("transcript_url")
                                                    .description("The URL of the transcript.")))))))
        .help("", "https://docs.bytechef.io/reference/components/fireflies_v1#get-transcripts")
        .perform(FirefliesGetTranscriptsAction::perform);

    private FirefliesGetTranscriptsAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> variables = new HashMap<>();

        Integer limit = inputParameters.getInteger(LIMIT);

        if (limit != null) {
            variables.put(LIMIT, limit);
        }

        return context.http(http -> http.post("/graphql"))
            .body(
                Body.of(
                    QUERY, TRANSCRIPTS_QUERY,
                    VARIABLES, variables))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
