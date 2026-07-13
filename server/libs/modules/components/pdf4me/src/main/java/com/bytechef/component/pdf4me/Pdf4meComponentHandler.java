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

package com.bytechef.component.pdf4me;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.pdf4me.action.Pdf4meMergePdfsAction;
import com.bytechef.component.pdf4me.connection.Pdf4meConnection;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class Pdf4meComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("pdf4me")
        .title("PDF4me")
        .version(1)
        .description("PDF4me is an API platform for PDF conversion, merging, and processing.")
        .customAction(true)
        .icon("path:assets/pdf4me.svg")
        .categories(ComponentCategory.HELPERS)
        .connection(Pdf4meConnection.CONNECTION_DEFINITION)
        .actions(Pdf4meMergePdfsAction.ACTION_DEFINITION)
        .clusterElements(tool(Pdf4meMergePdfsAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
