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

package com.bytechef.component.invoice.ninja.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.invoice.ninja.constant.InvoiceNinjaConstants.NAME;

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
public class InvoiceNinjaCreateClientAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createClient")
        .title("Create Client")
        .description("Creates a new client.")
        .properties(
            string(NAME)
                .label("Name")
                .description("The name of the client.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The created client.")
                            .properties(
                                string("id")
                                    .description("The id of the client."),
                                string("name")
                                    .description("The name of the client.")))))
        .help("", "https://docs.bytechef.io/reference/components/invoiceNinja_v1#create-client")
        .perform(InvoiceNinjaCreateClientAction::perform);

    private InvoiceNinjaCreateClientAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/clients"))
            .body(Body.of(NAME, inputParameters.getRequiredString(NAME)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
