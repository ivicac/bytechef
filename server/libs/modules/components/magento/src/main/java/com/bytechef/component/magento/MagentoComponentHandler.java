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

package com.bytechef.component.magento;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.magento.action.MagentoGetOrderAction;
import com.bytechef.component.magento.action.MagentoGetProductBySkuAction;
import com.bytechef.component.magento.connection.MagentoConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class MagentoComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("magento")
        .title("Magento")
        .version(1)
        .description("Magento (Adobe Commerce) is an open-source e-commerce platform.")
        .customAction(true)
        .icon("path:assets/magento.svg")
        .categories(ComponentCategory.E_COMMERCE)
        .connection(MagentoConnection.CONNECTION_DEFINITION)
        .actions(
            MagentoGetProductBySkuAction.ACTION_DEFINITION,
            MagentoGetOrderAction.ACTION_DEFINITION)
        .clusterElements(tool(MagentoGetProductBySkuAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
