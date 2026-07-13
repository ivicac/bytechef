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

package com.bytechef.component.pdf.co.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.integer;
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
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class PdfCoConvertHtmlToPdfAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("convertHtmlToPdf")
        .title("Convert HTML to PDF")
        .description("Converts HTML content to a PDF file and returns a link to the generated file.")
        .properties(
            string("html")
                .label("HTML")
                .description("The HTML content to convert.")
                .controlType(ControlType.TEXT_AREA)
                .required(true),
            string("name")
                .label("File Name")
                .description("The name of the generated PDF file.")
                .defaultValue("document.pdf")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("url"),
                        integer("pageCount"),
                        bool("error"),
                        string("name"))))
        .perform(PdfCoConvertHtmlToPdfAction::perform);

    private PdfCoConvertHtmlToPdfAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/pdf/convert/from/html"))
            .body(
                Body.of(
                    Map.of(
                        "html", inputParameters.getRequiredString("html"),
                        "name", inputParameters.getString("name", "document.pdf"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
