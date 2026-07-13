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

package com.bytechef.component.raindrop.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.raindrop.constant.RaindropConstants.LINK;
import static com.bytechef.component.raindrop.constant.RaindropConstants.TITLE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class RaindropCreateBookmarkAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createBookmark")
        .title("Create Bookmark")
        .description("Creates a new bookmark (raindrop).")
        .properties(
            string(LINK)
                .label("Link")
                .description("The URL of the bookmark.")
                .required(true),
            string(TITLE)
                .label("Title")
                .description("The title of the bookmark.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        bool("result")
                            .description("Whether the bookmark was created."),
                        object("item")
                            .description("The created bookmark."))))
        .help("", "https://docs.bytechef.io/reference/components/raindrop_v1#create-bookmark")
        .perform(RaindropCreateBookmarkAction::perform);

    private RaindropCreateBookmarkAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(LINK, inputParameters.getRequiredString(LINK));

        String title = inputParameters.getString(TITLE);

        if (title != null) {
            body.put(TITLE, title);
        }

        return context
            .http(http -> http.post("/raindrop"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
