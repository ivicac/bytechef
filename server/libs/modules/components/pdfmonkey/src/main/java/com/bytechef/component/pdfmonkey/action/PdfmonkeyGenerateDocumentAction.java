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

package com.bytechef.component.pdfmonkey.action;

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
import com.bytechef.component.definition.Property.ControlType;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class PdfmonkeyGenerateDocumentAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("generateDocument")
        .title("Generate Document")
        .description("Generates a PDF document from a template.")
        .properties(
            string("documentTemplateId")
                .label("Document Template ID")
                .description("The ID of the template to generate the document from.")
                .required(true),
            string("payload")
                .label("Payload")
                .description("The JSON payload with the dynamic data for the template.")
                .controlType(ControlType.TEXT_AREA)
                .required(false),
            string("filename")
                .label("File Name")
                .description("The name of the generated PDF file.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("document")
                            .properties(
                                string("id"),
                                string("status"),
                                string("download_url")))))
        .perform(PdfmonkeyGenerateDocumentAction::perform);

    private PdfmonkeyGenerateDocumentAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> document = new HashMap<>();

        document.put("document_template_id", inputParameters.getRequiredString("documentTemplateId"));
        document.put("status", "pending");

        if (inputParameters.getString("payload") != null) {
            document.put("payload", inputParameters.getString("payload"));
        }

        if (inputParameters.getString("filename") != null) {
            document.put("meta", "{\"_filename\":\"%s\"}".formatted(inputParameters.getString("filename")));
        }

        return context.http(http -> http.post("/documents"))
            .body(Body.of(Map.of("document", document)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
