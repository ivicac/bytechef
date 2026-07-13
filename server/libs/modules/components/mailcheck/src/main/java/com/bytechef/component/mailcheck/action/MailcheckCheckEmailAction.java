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

package com.bytechef.component.mailcheck.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.mailcheck.constant.MailcheckConstants.EMAIL;

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
public class MailcheckCheckEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("checkEmail")
        .title("Check Email")
        .description("Checks the validity and deliverability of an email address.")
        .properties(
            string(EMAIL)
                .label("Email")
                .description("The email address that will be checked.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("id")
                            .description("The id of the check."),
                        string("email")
                            .description("The checked email address."),
                        bool("mx")
                            .description("Whether the domain has valid MX records."),
                        bool("disposable")
                            .description("Whether the email address is disposable."),
                        bool("alias")
                            .description("Whether the email address is an alias."),
                        string("didYouMean")
                            .description("A suggested correction of the email address."))))
        .help("", "https://docs.bytechef.io/reference/components/mailcheck_v1#check-email")
        .perform(MailcheckCheckEmailAction::perform);

    private MailcheckCheckEmailAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context.http(http -> http.post("/singleEmail:check"))
            .body(Body.of(Map.of(EMAIL, inputParameters.getRequiredString(EMAIL))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
