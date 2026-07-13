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

package com.bytechef.component.kommo.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.kommo.constant.KommoConstants.LIMIT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class KommoListLeadsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listLeads")
        .title("List Leads")
        .description("Returns the leads of the Kommo account.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of leads to return per page.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("_embedded")
                            .description("The embedded resources.")
                            .properties(
                                array("leads")
                                    .description("The leads of the account.")
                                    .items(
                                        object()
                                            .properties(
                                                integer("id")
                                                    .description("The id of the lead."),
                                                string("name")
                                                    .description("The name of the lead."),
                                                integer("price")
                                                    .description("The price of the lead.")))))))
        .help("", "https://docs.bytechef.io/reference/components/kommo_v1#list-leads")
        .perform(KommoListLeadsAction::perform);

    private KommoListLeadsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/leads"))
            .queryParameters(LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
