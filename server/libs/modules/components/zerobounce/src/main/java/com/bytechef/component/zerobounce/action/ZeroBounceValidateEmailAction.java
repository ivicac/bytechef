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

package com.bytechef.component.zerobounce.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.zerobounce.constant.ZeroBounceConstants.EMAIL;
import static com.bytechef.component.zerobounce.constant.ZeroBounceConstants.IP_ADDRESS;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class ZeroBounceValidateEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("validateEmail")
        .title("Validate Email")
        .description("Validates an email address and returns its deliverability status.")
        .properties(
            string(EMAIL)
                .label("Email")
                .description("The email address that will be validated.")
                .required(true),
            string(IP_ADDRESS)
                .label("IP Address")
                .description("The IP address the email was signed up from.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("address")
                            .description("The validated email address."),
                        string("status")
                            .description("The deliverability status (e.g. valid, invalid, catch-all)."),
                        string("sub_status")
                            .description("The sub status of the validation."),
                        string("free_email")
                            .description("Whether the email is from a free provider."),
                        string("did_you_mean")
                            .description("A suggested correction of the email address."),
                        string("smtp_provider")
                            .description("The SMTP provider of the email address."))))
        .help("", "https://docs.bytechef.io/reference/components/zerobounce_v1#validate-email")
        .perform(ZeroBounceValidateEmailAction::perform);

    private ZeroBounceValidateEmailAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/validate"))
            .queryParameters(
                EMAIL, inputParameters.getRequiredString(EMAIL),
                IP_ADDRESS, inputParameters.getString(IP_ADDRESS))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
