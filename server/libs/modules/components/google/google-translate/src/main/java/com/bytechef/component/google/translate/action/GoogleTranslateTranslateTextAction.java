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

package com.bytechef.component.google.translate.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.google.translate.constant.GoogleTranslateConstants.SOURCE;
import static com.bytechef.component.google.translate.constant.GoogleTranslateConstants.TARGET;
import static com.bytechef.component.google.translate.constant.GoogleTranslateConstants.TEXT;

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
public class GoogleTranslateTranslateTextAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("translateText")
        .title("Translate Text")
        .description("Translates the text into the target language.")
        .properties(
            string(TEXT)
                .label("Text")
                .description("The text to translate.")
                .required(true),
            string(TARGET)
                .label("Target Language")
                .description("The two-letter code of the language to translate the text into (e.g. de).")
                .required(true),
            string(SOURCE)
                .label("Source Language")
                .description(
                    "The two-letter code of the language of the text. If empty, the language is detected " +
                        "automatically.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The translation data.")
                            .properties(
                                array("translations")
                                    .description("The translations of the text.")
                                    .items(
                                        object()
                                            .properties(
                                                string("translatedText")
                                                    .description("The translated text."),
                                                string("detectedSourceLanguage")
                                                    .description("The detected language of the text.")))))))
        .help("", "https://docs.bytechef.io/reference/components/googleTranslate_v1#translate-text")
        .perform(GoogleTranslateTranslateTextAction::perform);

    private GoogleTranslateTranslateTextAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put("q", inputParameters.getRequiredString(TEXT));
        body.put(TARGET, inputParameters.getRequiredString(TARGET));
        body.put("format", "text");

        String source = inputParameters.getString(SOURCE);

        if (source != null) {
            body.put(SOURCE, source);
        }

        return context.http(http -> http.post(""))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
