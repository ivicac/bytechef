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

package com.bytechef.component.mastodon.action;

import static com.bytechef.component.definition.ComponentDsl.action;
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
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class MastodonPostStatusAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("postStatus")
        .title("Post Status")
        .description("Publishes a status (toot) on your Mastodon account.")
        .properties(
            string("status")
                .label("Status")
                .description("The text content of the status.")
                .required(true),
            string("visibility")
                .label("Visibility")
                .description("The visibility of the status.")
                .options(
                    option("Public", "public"),
                    option("Unlisted", "unlisted"),
                    option("Private", "private"),
                    option("Direct", "direct"))
                .defaultValue("public")
                .required(false),
            string("spoilerText")
                .label("Spoiler Text")
                .description("Text shown as a warning before the actual content.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id"),
                        string("url"),
                        string("content"),
                        string("visibility"),
                        string("created_at"))))
        .perform(MastodonPostStatusAction::perform);

    private MastodonPostStatusAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put("status", inputParameters.getRequiredString("status"));
        body.put("visibility", inputParameters.getString("visibility", "public"));

        if (inputParameters.getString("spoilerText") != null) {
            body.put("spoiler_text", inputParameters.getString("spoilerText"));
        }

        return context.http(http -> http.post("/api/v1/statuses"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
