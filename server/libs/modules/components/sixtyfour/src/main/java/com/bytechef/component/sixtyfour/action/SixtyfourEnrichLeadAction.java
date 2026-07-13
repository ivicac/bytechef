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

package com.bytechef.component.sixtyfour.action;

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
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class SixtyfourEnrichLeadAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("enrichLead")
        .title("Enrich Lead")
        .description("Researches a person and returns enriched profile data.")
        .properties(
            string("name")
                .label("Name")
                .description("The full name of the person.")
                .required(true),
            string("company")
                .label("Company")
                .description("The company the person works at.")
                .required(false),
            string("title")
                .label("Title")
                .description("The job title of the person.")
                .required(false),
            string("location")
                .label("Location")
                .description("The location of the person.")
                .required(false),
            object("struct")
                .label("Structure")
                .description(
                    "A map of output field names to descriptions of the data to collect, e.g. " +
                        "{\"email\": \"The email address of the person\"}.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("structured_data"),
                        string("notes"))))
        .perform(SixtyfourEnrichLeadAction::perform);

    private SixtyfourEnrichLeadAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> leadInfo = new HashMap<>();

        leadInfo.put("name", inputParameters.getRequiredString("name"));

        if (inputParameters.getString("company") != null) {
            leadInfo.put("company", inputParameters.getString("company"));
        }

        if (inputParameters.getString("title") != null) {
            leadInfo.put("title", inputParameters.getString("title"));
        }

        if (inputParameters.getString("location") != null) {
            leadInfo.put("location", inputParameters.getString("location"));
        }

        return context.http(http -> http.post("/enrich-lead"))
            .body(
                Body.of(
                    Map.of(
                        "lead_info", leadInfo,
                        "struct", inputParameters.getRequiredMap("struct"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
