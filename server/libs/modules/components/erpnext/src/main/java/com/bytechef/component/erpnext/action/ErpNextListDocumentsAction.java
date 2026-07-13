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

package com.bytechef.component.erpnext.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.erpnext.constant.ErpNextConstants.DOCTYPE;
import static com.bytechef.component.erpnext.constant.ErpNextConstants.LIMIT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * @author Ivica Cardic
 */
public class ErpNextListDocumentsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listDocuments")
        .title("List Documents")
        .description("Returns the documents of the given doctype.")
        .properties(
            string(DOCTYPE)
                .label("Doctype")
                .description("The name of the ERPNext doctype (e.g. Customer, Sales Order).")
                .required(true),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of documents to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("data")
                            .description("The documents of the doctype.")
                            .items(
                                object()
                                    .properties(
                                        string("name")
                                            .description("The name (id) of the document."))))))
        .help("", "https://docs.bytechef.io/reference/components/erpNext_v1#list-documents")
        .perform(ErpNextListDocumentsAction::perform);

    private ErpNextListDocumentsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String doctype = URLEncoder.encode(inputParameters.getRequiredString(DOCTYPE), StandardCharsets.UTF_8)
            .replace("+", "%20");

        return context.http(http -> http.get("/resource/" + doctype))
            .queryParameters("limit_page_length", inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
