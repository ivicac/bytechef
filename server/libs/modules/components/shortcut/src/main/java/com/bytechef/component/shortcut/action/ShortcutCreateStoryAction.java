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

package com.bytechef.component.shortcut.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.shortcut.constant.ShortcutConstants.DESCRIPTION;
import static com.bytechef.component.shortcut.constant.ShortcutConstants.NAME;
import static com.bytechef.component.shortcut.constant.ShortcutConstants.STORY_TYPE;

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
public class ShortcutCreateStoryAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createStory")
        .title("Create Story")
        .description("Creates a new story in your Shortcut workspace.")
        .properties(
            string(NAME)
                .label("Name")
                .description("The name of the story.")
                .required(true),
            string(DESCRIPTION)
                .label("Description")
                .description("The description of the story.")
                .required(false),
            string(STORY_TYPE)
                .label("Story Type")
                .description("The type of the story.")
                .options(
                    option("Feature", "feature"),
                    option("Bug", "bug"),
                    option("Chore", "chore"))
                .defaultValue("feature")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("id")
                            .description("The id of the created story."),
                        string("name")
                            .description("The name of the created story."),
                        string("description")
                            .description("The description of the created story."),
                        string("story_type")
                            .description("The type of the created story."),
                        string("app_url")
                            .description("The URL of the story in the Shortcut application."),
                        string("created_at")
                            .description("The date and time the story was created."))))
        .help("", "https://docs.bytechef.io/reference/components/shortcut_v1#create-story")
        .perform(ShortcutCreateStoryAction::perform);

    private ShortcutCreateStoryAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(NAME, inputParameters.getRequiredString(NAME));

        String description = inputParameters.getString(DESCRIPTION);

        if (description != null) {
            body.put(DESCRIPTION, description);
        }

        String storyType = inputParameters.getString(STORY_TYPE);

        if (storyType != null) {
            body.put(STORY_TYPE, storyType);
        }

        return context.http(http -> http.post("/stories"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
