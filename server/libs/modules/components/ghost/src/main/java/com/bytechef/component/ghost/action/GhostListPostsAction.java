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

package com.bytechef.component.ghost.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.ghost.constant.GhostConstants.LIMIT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class GhostListPostsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listPosts")
        .title("List Posts")
        .description("Returns a list of published posts of the site.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of posts to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("posts")
                            .description("The published posts of the site.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the post."),
                                        string("title")
                                            .description("The title of the post."),
                                        string("slug")
                                            .description("The slug of the post."),
                                        string("url")
                                            .description("The URL of the post."),
                                        string("excerpt")
                                            .description("The excerpt of the post."),
                                        string("published_at")
                                            .description("The date and time when the post was published."))))))
        .help("", "https://docs.bytechef.io/reference/components/ghost_v1#list-posts")
        .perform(GhostListPostsAction::perform);

    private GhostListPostsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/posts/"))
            .queryParameters(LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
