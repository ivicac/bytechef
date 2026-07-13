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

package com.bytechef.component.brandfetch.action;

import static com.bytechef.component.brandfetch.constant.BrandfetchConstants.DOMAIN;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class BrandfetchGetBrandAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getBrand")
        .title("Get Brand")
        .description("Retrieves brand information such as logos, colors and fonts for a domain.")
        .properties(
            string(DOMAIN)
                .label("Domain")
                .description("The domain of the brand (e.g. bytechef.io).")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the brand."),
                        string("name")
                            .description("The name of the brand."),
                        string("domain")
                            .description("The domain of the brand."),
                        string("description")
                            .description("The description of the brand."),
                        array("logos")
                            .description("The logos of the brand.")
                            .items(
                                object()
                                    .properties(
                                        string("type")
                                            .description("The type of the logo."),
                                        string("theme")
                                            .description("The theme of the logo."))),
                        array("colors")
                            .description("The brand colors.")
                            .items(
                                object()
                                    .properties(
                                        string("hex")
                                            .description("The hex value of the color."),
                                        string("type")
                                            .description("The type of the color."))))))
        .help("", "https://docs.bytechef.io/reference/components/brandfetch_v1#get-brand")
        .perform(BrandfetchGetBrandAction::perform);

    private BrandfetchGetBrandAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get("/brands/%s".formatted(inputParameters.getRequiredString(DOMAIN))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
