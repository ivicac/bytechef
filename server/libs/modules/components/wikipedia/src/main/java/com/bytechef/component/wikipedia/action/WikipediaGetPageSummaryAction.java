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

package com.bytechef.component.wikipedia.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.wikipedia.constant.WikipediaConstants.LANGUAGE;
import static com.bytechef.component.wikipedia.constant.WikipediaConstants.TITLE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * @author Ivica Cardic
 */
public class WikipediaGetPageSummaryAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getPageSummary")
        .title("Get Page Summary")
        .description("Returns the summary of a Wikipedia page.")
        .properties(
            string(TITLE)
                .label("Title")
                .description("The title of the Wikipedia page (e.g. Alan Turing).")
                .required(true),
            string(LANGUAGE)
                .label("Language")
                .description("The two-letter language code of the Wikipedia edition (e.g. en, de).")
                .defaultValue("en")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("title")
                            .description("The title of the page."),
                        string("description")
                            .description("The short description of the page."),
                        string("extract")
                            .description("The summary of the page."))))
        .help("", "https://docs.bytechef.io/reference/components/wikipedia_v1#get-page-summary")
        .perform(WikipediaGetPageSummaryAction::perform);

    private WikipediaGetPageSummaryAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String title = URLEncoder.encode(
            inputParameters.getRequiredString(TITLE)
                .replace(' ', '_'),
            StandardCharsets.UTF_8);

        String language = inputParameters.getString(LANGUAGE, "en");

        return context
            .http(http -> http.get("https://%s.wikipedia.org/api/rest_v1/page/summary/%s".formatted(language, title)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
