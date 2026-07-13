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

package com.bytechef.component.apitemplate.io.action;

import static com.bytechef.component.apitemplate.io.constant.ApiTemplateIoConstants.DATA;
import static com.bytechef.component.apitemplate.io.constant.ApiTemplateIoConstants.TEMPLATE_ID;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

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
public class ApiTemplateIoCreatePdfAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createPdf")
        .title("Create PDF")
        .description("Creates a PDF document from a template and JSON data.")
        .properties(
            string(TEMPLATE_ID)
                .label("Template ID")
                .description("The id of the PDF template.")
                .required(true),
            object(DATA)
                .label("Data")
                .description("The JSON data used to populate the template.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("status")
                            .description("The status of the PDF generation."),
                        string("download_url")
                            .description("The URL where the generated PDF can be downloaded."),
                        string("transaction_ref")
                            .description("The reference id of the transaction."))))
        .help("", "https://docs.bytechef.io/reference/components/apiTemplateIo_v1#create-pdf")
        .perform(ApiTemplateIoCreatePdfAction::perform);

    private ApiTemplateIoCreatePdfAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context.http(http -> http.post("/create-pdf"))
            .queryParameters(TEMPLATE_ID, inputParameters.getRequiredString(TEMPLATE_ID))
            .body(Body.of(inputParameters.getRequiredMap(DATA, Object.class)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
