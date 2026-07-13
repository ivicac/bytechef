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

package com.bytechef.component.typefully.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.typefully.constant.TypefullyConstants.CONTENT;
import static com.bytechef.component.typefully.constant.TypefullyConstants.SHARE;
import static com.bytechef.component.typefully.constant.TypefullyConstants.THREADIFY;

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
public class TypefullyCreateDraftAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createDraft")
        .title("Create Draft")
        .description("Creates a new draft in Typefully.")
        .properties(
            string(CONTENT)
                .label("Content")
                .description(
                    "The content of the draft. Split into multiple tweets by adding four consecutive newlines.")
                .required(true),
            bool(THREADIFY)
                .label("Threadify")
                .description("Whether the content should be automatically split into multiple tweets.")
                .required(false),
            bool(SHARE)
                .label("Share")
                .description("Whether a share URL should be returned with the created draft.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("id")
                            .description("The id of the created draft."),
                        string("status")
                            .description("The status of the created draft."),
                        string("share_url")
                            .description("The share URL of the created draft."))))
        .help("", "https://docs.bytechef.io/reference/components/typefully_v1#create-draft")
        .perform(TypefullyCreateDraftAction::perform);

    private TypefullyCreateDraftAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(CONTENT, inputParameters.getRequiredString(CONTENT));

        Boolean threadify = inputParameters.getBoolean(THREADIFY);

        if (threadify != null) {
            body.put(THREADIFY, threadify);
        }

        Boolean share = inputParameters.getBoolean(SHARE);

        if (share != null) {
            body.put(SHARE, share);
        }

        return context.http(http -> http.post("/drafts/"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
