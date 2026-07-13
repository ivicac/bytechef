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

package com.bytechef.component.sendy.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.sendy.constant.SendyConstants.API_KEY;
import static com.bytechef.component.sendy.constant.SendyConstants.EMAIL;
import static com.bytechef.component.sendy.constant.SendyConstants.LIST;
import static com.bytechef.component.sendy.constant.SendyConstants.NAME;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.BodyContentType;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class SendyAddSubscriberAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("addSubscriber")
        .title("Add Subscriber")
        .description("Adds a subscriber to a list.")
        .properties(
            string(LIST)
                .label("List ID")
                .description("The encrypted id of the list the subscriber is added to.")
                .required(true),
            string(EMAIL)
                .label("Email")
                .description("The email address of the subscriber.")
                .required(true),
            string(NAME)
                .label("Name")
                .description("The name of the subscriber.")
                .required(false))
        .output(
            outputSchema(
                string()
                    .description("The result of the subscribe request, 1 on success.")))
        .help("", "https://docs.bytechef.io/reference/components/sendy_v1#add-subscriber")
        .perform(SendyAddSubscriberAction::perform);

    private SendyAddSubscriberAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put("api_key", connectionParameters.getRequiredString(API_KEY));
        body.put(LIST, inputParameters.getRequiredString(LIST));
        body.put(EMAIL, inputParameters.getRequiredString(EMAIL));
        body.put("boolean", "true");

        String name = inputParameters.getString(NAME);

        if (name != null) {
            body.put(NAME, name);
        }

        return context
            .http(http -> http.post("/subscribe"))
            .body(Body.of(body, BodyContentType.FORM_URL_ENCODED))
            .configuration(responseType(ResponseType.TEXT))
            .execute()
            .getBody();
    }
}
