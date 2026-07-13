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

package com.bytechef.component.facebook.pages.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class FacebookPagesGetPagesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getPages")
        .title("Get Pages")
        .description("Returns the Facebook pages managed by the authenticated user.")
        .output(
            outputSchema(
                object()
                    .properties(
                        array("data")
                            .description("The pages managed by the user.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the page."),
                                        string("name")
                                            .description("The name of the page."),
                                        string("category")
                                            .description("The category of the page."),
                                        string("access_token")
                                            .description("The page access token used to publish to the page."))))))
        .help("", "https://docs.bytechef.io/reference/components/facebookPages_v1#get-pages")
        .perform(FacebookPagesGetPagesAction::perform);

    private FacebookPagesGetPagesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/me/accounts"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
