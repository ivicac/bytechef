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

package com.bytechef.component.manychat.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
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
public class ManyChatGetPageInfoAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getPageInfo")
        .title("Get Page Info")
        .description("Returns information about the connected page.")
        .output(
            outputSchema(
                object()
                    .properties(
                        string("status"),
                        object("data")
                            .properties(
                                integer("id"),
                                string("name"),
                                string("category"),
                                string("timezone")))))
        .perform(ManyChatGetPageInfoAction::perform);

    private ManyChatGetPageInfoAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/fb/page/getInfo"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
