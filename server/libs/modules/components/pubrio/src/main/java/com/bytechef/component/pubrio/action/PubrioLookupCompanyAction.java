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

package com.bytechef.component.pubrio.action;

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
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class PubrioLookupCompanyAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("lookupCompany")
        .title("Lookup Company")
        .description("Looks up a company by domain or LinkedIn URL.")
        .properties(
            string("domain")
                .label("Domain")
                .description("The company domain, e.g. example.com.")
                .required(false),
            string("linkedinUrl")
                .label("LinkedIn URL")
                .description("The LinkedIn URL of the company.")
                .required(false))
        .output(outputSchema(object()))
        .perform(PubrioLookupCompanyAction::perform);

    private PubrioLookupCompanyAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        if (inputParameters.getString("domain") != null) {
            body.put("domain", inputParameters.getString("domain"));
        }

        if (inputParameters.getString("linkedinUrl") != null) {
            body.put("linkedin_url", inputParameters.getString("linkedinUrl"));
        }

        return context.http(http -> http.post("/companies/lookup"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
