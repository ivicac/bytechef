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

package com.bytechef.component.bannerbear.action;

import static com.bytechef.component.bannerbear.constant.BannerbearConstants.MODIFICATIONS;
import static com.bytechef.component.bannerbear.constant.BannerbearConstants.TEMPLATE;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
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
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class BannerbearCreateImageAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createImage")
        .title("Create Image")
        .description("Creates an image based on a template.")
        .properties(
            string(TEMPLATE)
                .label("Template ID")
                .description("The uid of the template to use.")
                .required(true),
            array(MODIFICATIONS)
                .label("Modifications")
                .description("The list of modifications applied to the template layers.")
                .items(
                    object()
                        .properties(
                            string("name")
                                .label("Name")
                                .description("The name of the template layer to modify.")
                                .required(true),
                            string("text")
                                .label("Text")
                                .description("The replacement text for a text layer.")
                                .required(false),
                            string("image_url")
                                .label("Image URL")
                                .description("The replacement image URL for an image layer.")
                                .required(false)))
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("uid")
                            .description("The uid of the created image."),
                        string("status")
                            .description("The rendering status of the image."),
                        string("image_url")
                            .description("The URL of the rendered image."),
                        string("template")
                            .description("The uid of the used template."))))
        .help("", "https://docs.bytechef.io/reference/components/bannerbear_v1#create-image")
        .perform(BannerbearCreateImageAction::perform);

    private BannerbearCreateImageAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/images"))
            .body(
                Body.of(
                    TEMPLATE, inputParameters.getRequiredString(TEMPLATE),
                    MODIFICATIONS, inputParameters.getRequiredList(MODIFICATIONS)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
