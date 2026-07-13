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

package com.bytechef.component.list.helper;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.list.helper.action.ListHelperAverageAction;
import com.bytechef.component.list.helper.action.ListHelperCountAction;
import com.bytechef.component.list.helper.action.ListHelperDeduplicateAction;
import com.bytechef.component.list.helper.action.ListHelperFlattenAction;
import com.bytechef.component.list.helper.action.ListHelperReverseAction;
import com.bytechef.component.list.helper.action.ListHelperSortAction;
import com.bytechef.component.list.helper.action.ListHelperSumAction;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class ListHelperComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("listHelper")
        .title("List Helper")
        .version(1)
        .description("Helper component which contains various actions for processing lists.")
        .icon("path:assets/list-helper.svg")
        .categories(ComponentCategory.HELPERS)
        .actions(
            ListHelperAverageAction.ACTION_DEFINITION,
            ListHelperCountAction.ACTION_DEFINITION,
            ListHelperDeduplicateAction.ACTION_DEFINITION,
            ListHelperFlattenAction.ACTION_DEFINITION,
            ListHelperReverseAction.ACTION_DEFINITION,
            ListHelperSortAction.ACTION_DEFINITION,
            ListHelperSumAction.ACTION_DEFINITION)
        .clusterElements(
            tool(ListHelperAverageAction.ACTION_DEFINITION),
            tool(ListHelperCountAction.ACTION_DEFINITION),
            tool(ListHelperDeduplicateAction.ACTION_DEFINITION),
            tool(ListHelperFlattenAction.ACTION_DEFINITION),
            tool(ListHelperReverseAction.ACTION_DEFINITION),
            tool(ListHelperSortAction.ACTION_DEFINITION),
            tool(ListHelperSumAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
