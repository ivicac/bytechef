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

package com.bytechef.component.bluesky.action;

import static com.bytechef.component.definition.Authorization.PASSWORD;
import static com.bytechef.component.definition.Authorization.USERNAME;
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
import java.time.Instant;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class BlueskyCreatePostAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createPost")
        .title("Create Post")
        .description("Publishes a post on your Bluesky account.")
        .properties(
            string("text")
                .label("Text")
                .description("The text content of the post.")
                .maxLength(300)
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("uri"),
                        string("cid"))))
        .perform(BlueskyCreatePostAction::perform);

    private BlueskyCreatePostAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<?, ?> session = context
            .http(http -> http.post("/com.atproto.server.createSession"))
            .body(
                Body.of(
                    Map.of(
                        "identifier", connectionParameters.getRequiredString(USERNAME),
                        "password", connectionParameters.getRequiredString(PASSWORD))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});

        String accessJwt = (String) session.get("accessJwt");
        String did = (String) session.get("did");

        return context.http(http -> http.post("/com.atproto.repo.createRecord"))
            .header("Authorization", "Bearer " + accessJwt)
            .body(
                Body.of(
                    Map.of(
                        "repo", did,
                        "collection", "app.bsky.feed.post",
                        "record", Map.of(
                            "$type", "app.bsky.feed.post",
                            "text", inputParameters.getRequiredString("text"),
                            "createdAt", Instant.now()
                                .toString()))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
