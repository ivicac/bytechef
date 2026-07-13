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

package com.bytechef.component.confluence.action;

import static com.bytechef.component.confluence.constant.ConfluenceConstants.BODY;
import static com.bytechef.component.confluence.constant.ConfluenceConstants.SPACE_ID;
import static com.bytechef.component.confluence.constant.ConfluenceConstants.TITLE;
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
public class ConfluenceCreatePageAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createPage")
        .title("Create Page")
        .description("Creates a page in the specified space.")
        .properties(
            string(SPACE_ID)
                .label("Space ID")
                .description("The id of the space where the page will be created.")
                .required(true),
            string(TITLE)
                .label("Title")
                .description("The title of the page.")
                .required(true),
            string(BODY)
                .label("Body")
                .description("The content of the page in Confluence storage format or plain text.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the created page."),
                        string("status")
                            .description("The status of the created page."),
                        string("title")
                            .description("The title of the created page."),
                        string("spaceId")
                            .description("The id of the space the page belongs to."))))
        .help("", "https://docs.bytechef.io/reference/components/confluence_v1#create-page")
        .perform(ConfluenceCreatePageAction::perform);

    private ConfluenceCreatePageAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        String body = inputParameters.getString(BODY, "");

        return context.http(http -> http.post("/pages"))
            .body(
                Body.of(
                    SPACE_ID, inputParameters.getRequiredString(SPACE_ID),
                    TITLE, inputParameters.getRequiredString(TITLE),
                    BODY, Map.of("representation", "storage", "value", body)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
