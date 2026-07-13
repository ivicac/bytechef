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

package com.bytechef.component.brightspace.action;

import static com.bytechef.component.definition.ComponentDsl.action;
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
public class BrightspaceWhoAmIAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("whoAmI")
        .title("Who Am I")
        .description("Returns the current user context of the authenticated user.")
        .output(
            outputSchema(
                object()
                    .properties(
                        string("Identifier")
                            .description("The id of the user."),
                        string("FirstName")
                            .description("The first name of the user."),
                        string("LastName")
                            .description("The last name of the user."),
                        string("UniqueName")
                            .description("The unique name of the user."))))
        .help("", "https://docs.bytechef.io/reference/components/brightspace_v1#who-am-i")
        .perform(BrightspaceWhoAmIAction::perform);

    private BrightspaceWhoAmIAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/lp/1.31/users/whoami"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
