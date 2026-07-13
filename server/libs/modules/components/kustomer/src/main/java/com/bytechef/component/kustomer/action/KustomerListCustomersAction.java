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

package com.bytechef.component.kustomer.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.kustomer.constant.KustomerConstants.PAGE_SIZE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class KustomerListCustomersAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listCustomers")
        .title("List Customers")
        .description("Returns the customers of the Kustomer organization.")
        .properties(
            integer(PAGE_SIZE)
                .label("Page Size")
                .description("The maximum number of customers to return per page.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("data")
                            .description("The customers of the organization.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the customer."),
                                        string("type")
                                            .description("The type of the resource."),
                                        object("attributes")
                                            .description("The attributes of the customer.")
                                            .properties(
                                                string("name")
                                                    .description("The name of the customer.")))))))
        .help("", "https://docs.bytechef.io/reference/components/kustomer_v1#list-customers")
        .perform(KustomerListCustomersAction::perform);

    private KustomerListCustomersAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/customers"))
            .queryParameters("pageSize", inputParameters.getInteger(PAGE_SIZE))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
