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

package com.bytechef.component.tiktok.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class TiktokListVideosAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listVideos")
        .title("List Videos")
        .description("Returns the videos of the authenticated TikTok user.")
        .properties(
            string("fields")
                .label("Fields")
                .description("Comma-separated list of video fields to return.")
                .defaultValue("id,title,create_time,share_url")
                .required(true),
            integer("maxCount")
                .label("Max Count")
                .description("The maximum number of videos to return (1-20).")
                .defaultValue(20)
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .properties(
                                array("videos")
                                    .items(
                                        object()
                                            .properties(
                                                string("id"),
                                                string("title"),
                                                string("share_url")))))))
        .perform(TiktokListVideosAction::perform);

    private TiktokListVideosAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/video/list/"))
            .queryParameters("fields", inputParameters.getRequiredString("fields"))
            .body(Body.of(Map.of("max_count", inputParameters.getInteger("maxCount", 20))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
