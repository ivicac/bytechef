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

package com.bytechef.component.customer.io;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.customer.io.action.CustomerIoListCampaignsAction;
import com.bytechef.component.customer.io.connection.CustomerIoConnection;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class CustomerIoComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("customerIo")
        .title("Customer.io")
        .version(1)
        .description("Customer.io is a customer engagement platform for automated messaging campaigns.")
        .customAction(true)
        .icon("path:assets/customer-io.svg")
        .categories(ComponentCategory.MARKETING_AUTOMATION)
        .connection(CustomerIoConnection.CONNECTION_DEFINITION)
        .actions(CustomerIoListCampaignsAction.ACTION_DEFINITION)
        .clusterElements(tool(CustomerIoListCampaignsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
