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

package com.bytechef.component.carbone.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class CarboneRenderTemplateAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("renderTemplate")
        .title("Render Template")
        .description("Renders a previously uploaded template with JSON data and returns a render ID.")
        .properties(
            string("templateId")
                .label("Template ID")
                .description("The ID of the template uploaded to Carbone.")
                .required(true),
            object("data")
                .label("Data")
                .description("The JSON data used to render the template.")
                .required(true),
            string("convertTo")
                .label("Convert To")
                .description("The output format, e.g. pdf, docx, xlsx.")
                .defaultValue("pdf")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        bool("success"),
                        object("data")
                            .properties(
                                string("renderId")))))
        .perform(CarboneRenderTemplateAction::perform);

    private CarboneRenderTemplateAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put("data", inputParameters.getRequiredMap("data"));
        body.put("convertTo", inputParameters.getString("convertTo", "pdf"));

        return context
            .http(http -> http.post("/render/%s".formatted(inputParameters.getRequiredString("templateId"))))
            .header("carbone-version", "5")
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
