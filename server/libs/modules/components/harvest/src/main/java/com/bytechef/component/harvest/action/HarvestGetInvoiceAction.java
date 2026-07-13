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

package com.bytechef.component.harvest.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.harvest.constant.HarvestConstants.INVOICE_ID;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class HarvestGetInvoiceAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getInvoice")
        .title("Get Invoice")
        .description("Retrieves an invoice by its id.")
        .properties(
            string(INVOICE_ID)
                .label("Invoice ID")
                .description("The id of the invoice that will be retrieved.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("id")
                            .description("The id of the invoice."),
                        string("number")
                            .description("The number of the invoice."),
                        object("client")
                            .description("The client of the invoice.")
                            .properties(
                                integer("id")
                                    .description("The id of the client."),
                                string("name")
                                    .description("The name of the client.")),
                        number("amount")
                            .description("The total amount of the invoice."),
                        number("due_amount")
                            .description("The outstanding amount of the invoice."),
                        string("state")
                            .description("The state of the invoice."),
                        string("issue_date")
                            .description("The issue date of the invoice."),
                        string("due_date")
                            .description("The due date of the invoice."))))
        .help("", "https://docs.bytechef.io/reference/components/harvest_v1#get-invoice")
        .perform(HarvestGetInvoiceAction::perform);

    private HarvestGetInvoiceAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get("/invoices/%s".formatted(inputParameters.getRequiredString(INVOICE_ID))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
