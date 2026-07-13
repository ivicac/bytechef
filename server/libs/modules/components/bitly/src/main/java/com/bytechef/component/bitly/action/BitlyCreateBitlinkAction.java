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

package com.bytechef.component.bitly.action;

import static com.bytechef.component.bitly.constant.BitlyConstants.DOMAIN;
import static com.bytechef.component.bitly.constant.BitlyConstants.LONG_URL;
import static com.bytechef.component.bitly.constant.BitlyConstants.TITLE;
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
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class BitlyCreateBitlinkAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createBitlink")
        .title("Create Bitlink")
        .description("Shortens a long URL into a Bitlink.")
        .properties(
            string(LONG_URL)
                .label("Long URL")
                .description("The long URL that will be shortened.")
                .required(true),
            string(DOMAIN)
                .label("Domain")
                .description("The branded short domain to use. Defaults to bit.ly.")
                .required(false),
            string(TITLE)
                .label("Title")
                .description("The title of the Bitlink.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The unique identifier of the Bitlink."),
                        string("link")
                            .description("The shortened Bitlink URL."),
                        string("long_url")
                            .description("The original long URL."),
                        string("title")
                            .description("The title of the Bitlink."),
                        string("created_at")
                            .description("The date and time the Bitlink was created."),
                        array("tags")
                            .description("The tags associated with the Bitlink.")
                            .items(string()))))
        .help("", "https://docs.bytechef.io/reference/components/bitly_v1#create-bitlink")
        .perform(BitlyCreateBitlinkAction::perform);

    private BitlyCreateBitlinkAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(LONG_URL, inputParameters.getRequiredString(LONG_URL));

        String domain = inputParameters.getString(DOMAIN);

        if (domain != null) {
            body.put(DOMAIN, domain);
        }

        String title = inputParameters.getString(TITLE);

        if (title != null) {
            body.put(TITLE, title);
        }

        return context.http(http -> http.post("/bitlinks"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
