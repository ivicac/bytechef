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

package com.bytechef.component.zuora.action;

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
public class ZuoraListProductsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listProducts")
        .title("List Products")
        .description("Returns the products in your Zuora product catalog.")
        .properties(
            integer("pageSize")
                .label("Page Size")
                .description("The number of products to return per page.")
                .defaultValue(20)
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("products")
                            .items(
                                object()
                                    .properties(
                                        string("id"),
                                        string("name"),
                                        string("sku"))))))
        .perform(ZuoraListProductsAction::perform);

    private ZuoraListProductsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/v1/catalog/products"))
            .queryParameters("page_size", inputParameters.getInteger("pageSize", 20))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
