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

package com.bytechef.component.leadmagic.action;

import static com.bytechef.component.definition.ComponentDsl.action;
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
public class LeadmagicFindEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("findEmail")
        .title("Find Email")
        .description("Finds the work email of a person based on name and company domain.")
        .properties(
            string("firstName")
                .label("First Name")
                .description("The first name of the person.")
                .required(true),
            string("lastName")
                .label("Last Name")
                .description("The last name of the person.")
                .required(true),
            string("domain")
                .label("Domain")
                .description("The company domain, e.g. example.com.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("email"),
                        string("status"),
                        string("company_name"))))
        .perform(LeadmagicFindEmailAction::perform);

    private LeadmagicFindEmailAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/v1/people/email-finder"))
            .body(
                Body.of(
                    Map.of(
                        "first_name", inputParameters.getRequiredString("firstName"),
                        "last_name", inputParameters.getRequiredString("lastName"),
                        "domain", inputParameters.getRequiredString("domain"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
