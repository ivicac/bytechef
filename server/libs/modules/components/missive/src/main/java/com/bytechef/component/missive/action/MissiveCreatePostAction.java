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

package com.bytechef.component.missive.action;

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
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class MissiveCreatePostAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createPost")
        .title("Create Post")
        .description("Creates a post in a conversation.")
        .properties(
            string("conversationSubject")
                .label("Conversation Subject")
                .description("The subject of the conversation the post is created in.")
                .required(false),
            string("username")
                .label("Username")
                .description("The username shown as the author of the post.")
                .required(false),
            string("markdown")
                .label("Markdown")
                .description("The content of the post in Markdown format.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("posts")
                            .properties(
                                string("id")))))
        .perform(MissiveCreatePostAction::perform);

    private MissiveCreatePostAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> post = new HashMap<>();

        post.put("markdown", inputParameters.getRequiredString("markdown"));

        if (inputParameters.getString("conversationSubject") != null) {
            post.put("conversation_subject", inputParameters.getString("conversationSubject"));
        }

        if (inputParameters.getString("username") != null) {
            post.put("username", inputParameters.getString("username"));
        }

        return context.http(http -> http.post("/posts"))
            .body(Body.of(Map.of("posts", post)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
