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

package com.bytechef.component.servicenow.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.servicenow.constant.ServiceNowConstants.DATA;
import static com.bytechef.component.servicenow.constant.ServiceNowConstants.TABLE_NAME;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class ServiceNowCreateRecordAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createRecord")
        .title("Create Record")
        .description("Creates a record in the specified table.")
        .properties(
            string(TABLE_NAME)
                .label("Table Name")
                .description("The name of the table where the record will be created (e.g. incident).")
                .required(true),
            object(DATA)
                .label("Data")
                .description("The field values of the record (e.g. short_description).")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("result")
                            .description("The created record.")
                            .properties(
                                string("sys_id")
                                    .description("The system id of the created record."),
                                string("number")
                                    .description("The number of the created record."),
                                string("sys_created_on")
                                    .description("The date and time the record was created.")))))
        .help("", "https://docs.bytechef.io/reference/components/servicenow_v1#create-record")
        .perform(ServiceNowCreateRecordAction::perform);

    private ServiceNowCreateRecordAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/table/%s".formatted(inputParameters.getRequiredString(TABLE_NAME))))
            .body(Body.of(inputParameters.getRequiredMap(DATA, Object.class)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
