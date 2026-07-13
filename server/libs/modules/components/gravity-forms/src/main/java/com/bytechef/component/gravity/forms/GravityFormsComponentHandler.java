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

package com.bytechef.component.gravity.forms;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.gravity.forms.action.GravityFormsListFormsAction;
import com.bytechef.component.gravity.forms.connection.GravityFormsConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class GravityFormsComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("gravityForms")
        .title("Gravity Forms")
        .version(1)
        .description("Gravity Forms is a form builder plugin for WordPress.")
        .customAction(true)
        .icon("path:assets/gravity-forms.svg")
        .categories(ComponentCategory.SURVEYS_AND_FEEDBACK)
        .connection(GravityFormsConnection.CONNECTION_DEFINITION)
        .actions(GravityFormsListFormsAction.ACTION_DEFINITION)
        .clusterElements(tool(GravityFormsListFormsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
