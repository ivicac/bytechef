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

package com.bytechef.component.getresponse.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.getresponse.constant.GetResponseConstants.CAMPAIGN_ID;
import static com.bytechef.component.getresponse.constant.GetResponseConstants.EMAIL;
import static com.bytechef.component.getresponse.constant.GetResponseConstants.NAME;

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
public class GetResponseCreateContactAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createContact")
        .title("Create Contact")
        .description("Creates a new contact in a campaign (list).")
        .properties(
            string(CAMPAIGN_ID)
                .label("Campaign ID")
                .description("The id of the campaign (list) the contact is added to.")
                .required(true),
            string(EMAIL)
                .label("Email")
                .description("The email address of the contact.")
                .required(true),
            string(NAME)
                .label("Name")
                .description("The name of the contact.")
                .required(false))
        .help("", "https://docs.bytechef.io/reference/components/getresponse_v1#create-contact")
        .perform(GetResponseCreateContactAction::perform);

    private GetResponseCreateContactAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put(EMAIL, inputParameters.getRequiredString(EMAIL));
        body.put("campaign", Map.of(CAMPAIGN_ID, inputParameters.getRequiredString(CAMPAIGN_ID)));

        String name = inputParameters.getString(NAME);

        if (name != null) {
            body.put(NAME, name);
        }

        return context
            .http(http -> http.post("/contacts"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
