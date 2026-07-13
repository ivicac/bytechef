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

package com.bytechef.component.razorpay.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
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
public class RazorpayListPaymentsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listPayments")
        .title("List Payments")
        .description("Returns the payments captured in your Razorpay account.")
        .properties(
            integer("count")
                .label("Count")
                .description("The maximum number of payments to return.")
                .defaultValue(10)
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("count"),
                        array("items")
                            .items(
                                object()
                                    .properties(
                                        string("id"),
                                        integer("amount"),
                                        string("currency"),
                                        string("status"),
                                        string("method"))))))
        .perform(RazorpayListPaymentsAction::perform);

    private RazorpayListPaymentsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/payments"))
            .queryParameters("count", inputParameters.getInteger("count", 10))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
