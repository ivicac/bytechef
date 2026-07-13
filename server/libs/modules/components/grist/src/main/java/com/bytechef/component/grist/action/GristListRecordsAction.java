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

package com.bytechef.component.grist.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.grist.constant.GristConstants.DOC_ID;
import static com.bytechef.component.grist.constant.GristConstants.LIMIT;
import static com.bytechef.component.grist.constant.GristConstants.TABLE_ID;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class GristListRecordsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listRecords")
        .title("List Records")
        .description("Returns the records of a table.")
        .properties(
            string(DOC_ID)
                .label("Document ID")
                .description("The id of the document.")
                .required(true),
            string(TABLE_ID)
                .label("Table ID")
                .description("The id of the table.")
                .required(true),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of records to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("records")
                            .description("The records of the table.")
                            .items(
                                object()
                                    .properties(
                                        integer("id")
                                            .description("The id of the record."),
                                        object("fields")
                                            .description("The fields of the record."))))))
        .help("", "https://docs.bytechef.io/reference/components/grist_v1#list-records")
        .perform(GristListRecordsAction::perform);

    private GristListRecordsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get(
                "/docs/%s/tables/%s/records".formatted(
                    inputParameters.getRequiredString(DOC_ID),
                    inputParameters.getRequiredString(TABLE_ID))))
            .queryParameters(LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
