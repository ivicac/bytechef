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

package com.bytechef.component.microsoft.dataverse.action;

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
import java.util.ArrayList;
import java.util.List;

/**
 * @author Ivica Cardic
 */
public class MicrosoftDataverseListRecordsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listRecords")
        .title("List Records")
        .description("Returns the records of a Dataverse table.")
        .properties(
            string("entitySetName")
                .label("Entity Set Name")
                .description("The plural entity set name of the table, e.g. accounts, contacts.")
                .required(true),
            string("select")
                .label("Select")
                .description("Comma-separated list of columns to return.")
                .required(false),
            string("filter")
                .label("Filter")
                .description("An OData $filter expression.")
                .required(false),
            integer("top")
                .label("Top")
                .description("The maximum number of records to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("value")
                            .items(object()))))
        .perform(MicrosoftDataverseListRecordsAction::perform);

    private MicrosoftDataverseListRecordsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        List<Object> queryParameters = new ArrayList<>();

        if (inputParameters.getString("select") != null) {
            queryParameters.add("$select");
            queryParameters.add(inputParameters.getString("select"));
        }

        if (inputParameters.getString("filter") != null) {
            queryParameters.add("$filter");
            queryParameters.add(inputParameters.getString("filter"));
        }

        if (inputParameters.getInteger("top") != null) {
            queryParameters.add("$top");
            queryParameters.add(inputParameters.getInteger("top"));
        }

        return context
            .http(http -> http.get("/%s".formatted(inputParameters.getRequiredString("entitySetName"))))
            .queryParameters(queryParameters.toArray())
            .header("OData-MaxVersion", "4.0")
            .header("OData-Version", "4.0")
            .header("Accept", "application/json")
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
