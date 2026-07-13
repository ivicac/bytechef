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

package com.bytechef.component.neverbounce.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.neverbounce.constant.NeverBounceConstants.EMAIL;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class NeverBounceVerifyEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("verifyEmail")
        .title("Verify Email")
        .description("Verifies a single email address.")
        .properties(
            string(EMAIL)
                .label("Email")
                .description("The email address to verify.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("status")
                            .description("The status of the API request."),
                        string("result")
                            .description("The verification result: valid, invalid, disposable, catchall or unknown."),
                        array("flags")
                            .description("The flags describing the email address.")
                            .items(string()),
                        string("suggested_correction")
                            .description("A suggested correction of the email address."))))
        .help("", "https://docs.bytechef.io/reference/components/neverBounce_v1#verify-email")
        .perform(NeverBounceVerifyEmailAction::perform);

    private NeverBounceVerifyEmailAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.get("/single/check"))
            .queryParameters(EMAIL, inputParameters.getRequiredString(EMAIL))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
