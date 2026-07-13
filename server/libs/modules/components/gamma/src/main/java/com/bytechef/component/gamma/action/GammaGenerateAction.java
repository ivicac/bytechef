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

package com.bytechef.component.gamma.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.gamma.constant.GammaConstants.FORMAT;
import static com.bytechef.component.gamma.constant.GammaConstants.INPUT_TEXT;

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
public class GammaGenerateAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("generate")
        .title("Generate")
        .description("Starts a generation of a presentation, document or social content from text.")
        .properties(
            string(INPUT_TEXT)
                .label("Input Text")
                .description("The text the content is generated from.")
                .required(true),
            string(FORMAT)
                .label("Format")
                .description("The format of the generated content.")
                .options(
                    option("Presentation", "presentation"),
                    option("Document", "document"),
                    option("Social", "social"))
                .defaultValue("presentation")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("generationId")
                            .description("The id used to fetch the generation result."))))
        .help("", "https://docs.bytechef.io/reference/components/gamma_v1#generate")
        .perform(GammaGenerateAction::perform);

    private GammaGenerateAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/generations"))
            .body(
                Body.of(
                    INPUT_TEXT, inputParameters.getRequiredString(INPUT_TEXT),
                    FORMAT, inputParameters.getRequiredString(FORMAT)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
