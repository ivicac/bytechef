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

package com.bytechef.component.instagram.business.action;

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
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class InstagramBusinessPublishPhotoAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("publishPhoto")
        .title("Publish Photo")
        .description("Publishes a photo to an Instagram business account.")
        .properties(
            string("instagramAccountId")
                .label("Instagram Account ID")
                .description("The ID of the Instagram business account. Use the List Accounts action to find it.")
                .required(true),
            string("imageUrl")
                .label("Image URL")
                .description("The public URL of the JPEG image to publish.")
                .required(true),
            string("caption")
                .label("Caption")
                .description("The caption of the post.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id"))))
        .perform(InstagramBusinessPublishPhotoAction::perform);

    private InstagramBusinessPublishPhotoAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String instagramAccountId = inputParameters.getRequiredString("instagramAccountId");

        Map<String, Object> containerBody = new HashMap<>();

        containerBody.put("image_url", inputParameters.getRequiredString("imageUrl"));

        if (inputParameters.getString("caption") != null) {
            containerBody.put("caption", inputParameters.getString("caption"));
        }

        Map<?, ?> container = context
            .http(http -> http.post("/%s/media".formatted(instagramAccountId)))
            .body(Body.of(containerBody))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});

        return context
            .http(http -> http.post("/%s/media_publish".formatted(instagramAccountId)))
            .body(Body.of(Map.of("creation_id", (String) container.get("id"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
