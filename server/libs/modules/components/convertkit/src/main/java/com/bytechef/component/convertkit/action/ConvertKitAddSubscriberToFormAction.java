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

package com.bytechef.component.convertkit.action;

import static com.bytechef.component.convertkit.constant.ConvertKitConstants.EMAIL;
import static com.bytechef.component.convertkit.constant.ConvertKitConstants.FIRST_NAME;
import static com.bytechef.component.convertkit.constant.ConvertKitConstants.FORM_ID;
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
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class ConvertKitAddSubscriberToFormAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("addSubscriberToForm")
        .title("Add Subscriber to Form")
        .description("Subscribes an email address to a form.")
        .properties(
            string(FORM_ID)
                .label("Form ID")
                .description("The id of the form to subscribe to.")
                .required(true),
            string(EMAIL)
                .label("Email")
                .description("The email address of the subscriber.")
                .required(true),
            string(FIRST_NAME)
                .label("First Name")
                .description("The first name of the subscriber.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("subscription")
                            .description("The created subscription."))))
        .help("", "https://docs.bytechef.io/reference/components/convertkit_v1#add-subscriber-to-form")
        .perform(ConvertKitAddSubscriberToFormAction::perform);

    private ConvertKitAddSubscriberToFormAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(EMAIL, inputParameters.getRequiredString(EMAIL));

        String firstName = inputParameters.getString(FIRST_NAME);

        if (firstName != null) {
            body.put(FIRST_NAME, firstName);
        }

        return context
            .http(http -> http.post(
                "/forms/%s/subscribe".formatted(inputParameters.getRequiredString(FORM_ID))))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
