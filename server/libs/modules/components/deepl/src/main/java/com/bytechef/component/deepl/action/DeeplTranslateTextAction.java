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

package com.bytechef.component.deepl.action;

import static com.bytechef.component.deepl.constant.DeeplConstants.SOURCE_LANG;
import static com.bytechef.component.deepl.constant.DeeplConstants.TARGET_LANG;
import static com.bytechef.component.deepl.constant.DeeplConstants.TEXT;
import static com.bytechef.component.deepl.constant.DeeplConstants.TRANSLATIONS;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class DeeplTranslateTextAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("translateText")
        .title("Translate Text")
        .description("Translates text into the specified target language.")
        .properties(
            string(TEXT)
                .label("Text")
                .description("The text that will be translated.")
                .required(true),
            string(TARGET_LANG)
                .label("Target Language")
                .description("The language into which the text will be translated.")
                .options(
                    option("Bulgarian", "BG"),
                    option("Chinese", "ZH"),
                    option("Czech", "CS"),
                    option("Danish", "DA"),
                    option("Dutch", "NL"),
                    option("English (British)", "EN-GB"),
                    option("English (American)", "EN-US"),
                    option("Estonian", "ET"),
                    option("Finnish", "FI"),
                    option("French", "FR"),
                    option("German", "DE"),
                    option("Greek", "EL"),
                    option("Hungarian", "HU"),
                    option("Indonesian", "ID"),
                    option("Italian", "IT"),
                    option("Japanese", "JA"),
                    option("Korean", "KO"),
                    option("Latvian", "LV"),
                    option("Lithuanian", "LT"),
                    option("Norwegian", "NB"),
                    option("Polish", "PL"),
                    option("Portuguese (Brazilian)", "PT-BR"),
                    option("Portuguese (European)", "PT-PT"),
                    option("Romanian", "RO"),
                    option("Russian", "RU"),
                    option("Slovak", "SK"),
                    option("Slovenian", "SL"),
                    option("Spanish", "ES"),
                    option("Swedish", "SV"),
                    option("Turkish", "TR"),
                    option("Ukrainian", "UK"))
                .required(true),
            string(SOURCE_LANG)
                .label("Source Language")
                .description("The language of the text to translate. If not set, DeepL detects it automatically.")
                .options(
                    option("Bulgarian", "BG"),
                    option("Chinese", "ZH"),
                    option("Czech", "CS"),
                    option("Danish", "DA"),
                    option("Dutch", "NL"),
                    option("English", "EN"),
                    option("Estonian", "ET"),
                    option("Finnish", "FI"),
                    option("French", "FR"),
                    option("German", "DE"),
                    option("Greek", "EL"),
                    option("Hungarian", "HU"),
                    option("Indonesian", "ID"),
                    option("Italian", "IT"),
                    option("Japanese", "JA"),
                    option("Korean", "KO"),
                    option("Latvian", "LV"),
                    option("Lithuanian", "LT"),
                    option("Norwegian", "NB"),
                    option("Polish", "PL"),
                    option("Portuguese", "PT"),
                    option("Romanian", "RO"),
                    option("Russian", "RU"),
                    option("Slovak", "SK"),
                    option("Slovenian", "SL"),
                    option("Spanish", "ES"),
                    option("Swedish", "SV"),
                    option("Turkish", "TR"),
                    option("Ukrainian", "UK"))
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array(TRANSLATIONS)
                            .description("The list of translations.")
                            .items(
                                object()
                                    .properties(
                                        string("detected_source_language")
                                            .description("The language detected in the source text."),
                                        string("text")
                                            .description("The translated text."))))))
        .help("", "https://docs.bytechef.io/reference/components/deepl_v1#translate-text")
        .perform(DeeplTranslateTextAction::perform);

    private DeeplTranslateTextAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(TEXT, List.of(inputParameters.getRequiredString(TEXT)));
        body.put(TARGET_LANG, inputParameters.getRequiredString(TARGET_LANG));

        String sourceLang = inputParameters.getString(SOURCE_LANG);

        if (sourceLang != null) {
            body.put(SOURCE_LANG, sourceLang);
        }

        return context.http(http -> http.post("/translate"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
