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

package com.bytechef.component.mindee.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.mindee.constant.MindeeConstants.DOCUMENT;

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
public class MindeeParseInvoiceAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("parseInvoice")
        .title("Parse Invoice")
        .description("Extracts structured data from an invoice document.")
        .properties(
            string(DOCUMENT)
                .label("Document URL")
                .description("The public URL of the invoice document to parse.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("document")
                            .description("The parsed document with extracted invoice data."),
                        object("api_request")
                            .description("The metadata of the API request."))))
        .help("", "https://docs.bytechef.io/reference/components/mindee_v1#parse-invoice")
        .perform(MindeeParseInvoiceAction::perform);

    private MindeeParseInvoiceAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/products/mindee/invoices/v4/predict"))
            .body(Body.of(DOCUMENT, inputParameters.getRequiredString(DOCUMENT)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
