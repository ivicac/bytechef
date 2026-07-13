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

package com.bytechef.component.nocobase.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.nocobase.constant.NocoBaseConstants.COLLECTION;
import static com.bytechef.component.nocobase.constant.NocoBaseConstants.DATA;

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
public class NocoBaseCreateRecordAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createRecord")
        .title("Create Record")
        .description("Creates a record in the specified collection.")
        .properties(
            string(COLLECTION)
                .label("Collection")
                .description("The name of the collection where the record will be created.")
                .required(true),
            object(DATA)
                .label("Data")
                .description("The field values of the record.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The created record.")
                            .properties(
                                integer("id")
                                    .description("The id of the created record."),
                                string("createdAt")
                                    .description("The date and time the record was created."),
                                string("updatedAt")
                                    .description("The date and time the record was last updated.")))))
        .help("", "https://docs.bytechef.io/reference/components/nocobase_v1#create-record")
        .perform(NocoBaseCreateRecordAction::perform);

    private NocoBaseCreateRecordAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/%s:create".formatted(inputParameters.getRequiredString(COLLECTION))))
            .body(Body.of(inputParameters.getRequiredMap(DATA, Object.class)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
