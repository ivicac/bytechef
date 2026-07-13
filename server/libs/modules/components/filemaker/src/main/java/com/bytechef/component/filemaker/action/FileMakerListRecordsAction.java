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

package com.bytechef.component.filemaker.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.filemaker.constant.FileMakerConstants.DATABASE;
import static com.bytechef.component.filemaker.constant.FileMakerConstants.LAYOUT;
import static com.bytechef.component.filemaker.constant.FileMakerConstants.LIMIT;
import static com.bytechef.component.filemaker.constant.FileMakerConstants.PASSWORD;
import static com.bytechef.component.filemaker.constant.FileMakerConstants.USERNAME;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class FileMakerListRecordsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listRecords")
        .title("List Records")
        .description("Returns the records of the layout.")
        .properties(
            string(LAYOUT)
                .label("Layout")
                .description("The name of the FileMaker layout.")
                .required(true),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of records to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("response")
                            .description("The response containing the records of the layout."))))
        .help("", "https://docs.bytechef.io/reference/components/fileMaker_v1#list-records")
        .perform(FileMakerListRecordsAction::perform);

    private FileMakerListRecordsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String database = connectionParameters.getRequiredString(DATABASE);

        Base64.Encoder encoder = Base64.getEncoder();

        String credentials = encoder.encodeToString(
            (connectionParameters.getRequiredString(USERNAME) + ":" +
                connectionParameters.getRequiredString(PASSWORD)).getBytes(StandardCharsets.UTF_8));

        Object sessionResult = context
            .http(http -> http.post("/databases/%s/sessions".formatted(database)))
            .header("Authorization", "Basic " + credentials)
            .body(Body.of(Map.of()))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();

        Object token = ((Map<?, ?>) ((Map<?, ?>) sessionResult).get("response")).get("token");

        return context
            .http(http -> http.get(
                "/databases/%s/layouts/%s/records".formatted(
                    database, inputParameters.getRequiredString(LAYOUT))))
            .header("Authorization", "Bearer " + token)
            .queryParameters("_limit", inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
