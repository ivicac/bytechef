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

package com.bytechef.component.chargebee.action;

import static com.bytechef.component.chargebee.constant.ChargebeeConstants.LIMIT;
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
public class ChargebeeListCustomersAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listCustomers")
        .title("List Customers")
        .description("Lists the customers of the Chargebee site.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of customers to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("list")
                            .description("The customers of the site.")
                            .items(
                                object()
                                    .properties(
                                        object("customer")
                                            .description("The customer.")
                                            .properties(
                                                string("id")
                                                    .description("The id of the customer."),
                                                string("first_name")
                                                    .description("The first name of the customer."),
                                                string("last_name")
                                                    .description("The last name of the customer."),
                                                string("email")
                                                    .description("The email address of the customer.")))),
                        string("next_offset")
                            .description("The offset for the next page of results."))))
        .help("", "https://docs.bytechef.io/reference/components/chargebee_v1#list-customers")
        .perform(ChargebeeListCustomersAction::perform);

    private ChargebeeListCustomersAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/customers"))
            .queryParameters(LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
