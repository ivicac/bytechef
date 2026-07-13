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

package com.bytechef.component.odoo.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.odoo.constant.OdooConstants.API_KEY;
import static com.bytechef.component.odoo.constant.OdooConstants.DATABASE;
import static com.bytechef.component.odoo.constant.OdooConstants.FIELDS;
import static com.bytechef.component.odoo.constant.OdooConstants.LIMIT;
import static com.bytechef.component.odoo.constant.OdooConstants.MODEL;
import static com.bytechef.component.odoo.constant.OdooConstants.USERNAME;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class OdooSearchReadRecordsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("searchReadRecords")
        .title("Search Read Records")
        .description("Searches and reads records of the given model.")
        .properties(
            string(MODEL)
                .label("Model")
                .description("The technical name of the Odoo model (e.g. res.partner).")
                .required(true),
            array(FIELDS)
                .label("Fields")
                .description("The names of the fields to return. If empty, all fields are returned.")
                .items(string())
                .required(false),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of records to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("result")
                            .description("The records of the model.")
                            .items(
                                object()
                                    .properties(
                                        integer("id")
                                            .description("The id of the record."))))))
        .help("", "https://docs.bytechef.io/reference/components/odoo_v1#search-read-records")
        .perform(OdooSearchReadRecordsAction::perform);

    private OdooSearchReadRecordsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String database = connectionParameters.getRequiredString(DATABASE);
        String apiKey = connectionParameters.getRequiredString(API_KEY);

        Object authenticateResult = context.http(http -> http.post("/jsonrpc"))
            .body(
                Body.of(
                    Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "call",
                        "params",
                        Map.of(
                            "service", "common",
                            "method", "authenticate",
                            "args",
                            List.of(
                                database, connectionParameters.getRequiredString(USERNAME), apiKey, Map.of())))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();

        Object uid = ((Map<?, ?>) authenticateResult).get("result");

        Map<String, Object> keywordArguments = new HashMap<>();

        List<String> fields = inputParameters.getList(FIELDS, String.class, List.of());

        if (!fields.isEmpty()) {
            keywordArguments.put(FIELDS, fields);
        }

        Integer limit = inputParameters.getInteger(LIMIT);

        if (limit != null) {
            keywordArguments.put(LIMIT, limit);
        }

        return context.http(http -> http.post("/jsonrpc"))
            .body(
                Body.of(
                    Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "call",
                        "params",
                        Map.of(
                            "service", "object",
                            "method", "execute_kw",
                            "args",
                            List.of(
                                database, uid, apiKey, inputParameters.getRequiredString(MODEL), "search_read",
                                List.of(List.of()), keywordArguments)))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
