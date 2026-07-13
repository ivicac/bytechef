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

package com.bytechef.component.discourse.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.discourse.constant.DiscourseConstants.CATEGORY;
import static com.bytechef.component.discourse.constant.DiscourseConstants.RAW;
import static com.bytechef.component.discourse.constant.DiscourseConstants.TITLE;

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
public class DiscourseCreatePostAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createPost")
        .title("Create Post")
        .description("Creates a new topic post.")
        .properties(
            string(TITLE)
                .label("Title")
                .description("The title of the topic.")
                .required(true),
            string(RAW)
                .label("Content")
                .description("The content of the post in Markdown.")
                .required(true),
            integer(CATEGORY)
                .label("Category ID")
                .description("The id of the category the topic is created in.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("id")
                            .description("The id of the created post."),
                        integer("topic_id")
                            .description("The id of the created topic."),
                        string("username")
                            .description("The username of the post author."),
                        string("created_at")
                            .description("The date the post was created."))))
        .help("", "https://docs.bytechef.io/reference/components/discourse_v1#create-post")
        .perform(DiscourseCreatePostAction::perform);

    private DiscourseCreatePostAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(TITLE, inputParameters.getRequiredString(TITLE));
        body.put(RAW, inputParameters.getRequiredString(RAW));

        Integer category = inputParameters.getInteger(CATEGORY);

        if (category != null) {
            body.put(CATEGORY, category);
        }

        return context
            .http(http -> http.post("/posts.json"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
