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

package com.bytechef.component.jina.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.jina.constant.JinaConstants.URL;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class JinaReadUrlAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("readUrl")
        .title("Read URL")
        .description("Reads a URL with the Jina Reader and returns LLM-friendly content.")
        .properties(
            string(URL)
                .label("URL")
                .description("The URL of the web page to read.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("code")
                            .description("The status code of the response."),
                        object("data")
                            .description("The extracted content.")
                            .properties(
                                string("title")
                                    .description("The title of the page."),
                                string("content")
                                    .description("The content of the page as markdown.")))))
        .help("", "https://docs.bytechef.io/reference/components/jina_v1#read-url")
        .perform(JinaReadUrlAction::perform);

    private JinaReadUrlAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/"))
            .header("Accept", "application/json")
            .body(Body.of(Map.of(URL, inputParameters.getRequiredString(URL))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
