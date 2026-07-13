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

package com.bytechef.component.bigcommerce;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.bigcommerce.action.BigCommerceListCustomersAction;
import com.bytechef.component.bigcommerce.action.BigCommerceListProductsAction;
import com.bytechef.component.bigcommerce.connection.BigCommerceConnection;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class BigCommerceComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("bigCommerce")
        .title("BigCommerce")
        .version(1)
        .description("BigCommerce is a SaaS e-commerce platform for building and managing online stores.")
        .customAction(true)
        .icon("path:assets/bigcommerce.svg")
        .categories(ComponentCategory.E_COMMERCE)
        .connection(BigCommerceConnection.CONNECTION_DEFINITION)
        .actions(
            BigCommerceListProductsAction.ACTION_DEFINITION,
            BigCommerceListCustomersAction.ACTION_DEFINITION)
        .clusterElements(tool(BigCommerceListProductsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
