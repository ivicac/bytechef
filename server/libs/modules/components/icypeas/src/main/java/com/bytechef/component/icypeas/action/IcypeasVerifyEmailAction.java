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

package com.bytechef.component.icypeas.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
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
public class IcypeasVerifyEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("verifyEmail")
        .title("Verify Email")
        .description("Launches a verification of an email address and returns the search item.")
        .properties(
            string("email")
                .label("Email")
                .description("The email address to verify.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        bool("success"),
                        object("item")
                            .properties(
                                string("_id"),
                                string("status")))))
        .perform(IcypeasVerifyEmailAction::perform);

    private IcypeasVerifyEmailAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/email-verification"))
            .body(Body.of(Map.of("email", inputParameters.getRequiredString("email"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
