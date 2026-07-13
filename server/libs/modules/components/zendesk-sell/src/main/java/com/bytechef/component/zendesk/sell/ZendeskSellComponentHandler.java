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

package com.bytechef.component.zendesk.sell;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.zendesk.sell.action.ZendeskSellListContactsAction;
import com.bytechef.component.zendesk.sell.connection.ZendeskSellConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class ZendeskSellComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("zendeskSell")
        .title("Zendesk Sell")
        .version(1)
        .description("Zendesk Sell is a sales CRM for pipeline management and sales automation.")
        .customAction(true)
        .icon("path:assets/zendesk-sell.svg")
        .categories(ComponentCategory.CRM)
        .connection(ZendeskSellConnection.CONNECTION_DEFINITION)
        .actions(ZendeskSellListContactsAction.ACTION_DEFINITION)
        .clusterElements(tool(ZendeskSellListContactsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
