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

package com.bytechef.component.dub.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.dub.constant.DubConstants.DOMAIN;
import static com.bytechef.component.dub.constant.DubConstants.KEY;
import static com.bytechef.component.dub.constant.DubConstants.URL;

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
public class DubCreateLinkAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createLink")
        .title("Create Link")
        .description("Creates a new short link.")
        .properties(
            string(URL)
                .label("Destination URL")
                .description("The destination URL of the short link.")
                .required(true),
            string(DOMAIN)
                .label("Domain")
                .description("The domain of the short link. Defaults to the workspace's primary domain.")
                .required(false),
            string(KEY)
                .label("Key")
                .description("The short link slug. If not provided, a random key is generated.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the created link."),
                        string("domain")
                            .description("The domain of the created link."),
                        string("key")
                            .description("The slug of the created link."),
                        string("shortLink")
                            .description("The full short link URL."),
                        string("url")
                            .description("The destination URL of the created link."))))
        .help("", "https://docs.bytechef.io/reference/components/dub_v1#create-link")
        .perform(DubCreateLinkAction::perform);

    private DubCreateLinkAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(URL, inputParameters.getRequiredString(URL));

        String domain = inputParameters.getString(DOMAIN);

        if (domain != null) {
            body.put(DOMAIN, domain);
        }

        String key = inputParameters.getString(KEY);

        if (key != null) {
            body.put(KEY, key);
        }

        return context.http(http -> http.post("/links"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
