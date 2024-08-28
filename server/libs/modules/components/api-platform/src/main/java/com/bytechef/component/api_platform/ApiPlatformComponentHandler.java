/*
 * Copyright 2023-present ByteChef Inc.
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

package com.bytechef.component.api_platform;

import static com.bytechef.component.api_platform.constant.ApiPlatformConstants.API_PLATFORM;
import static com.bytechef.component.definition.ComponentDSL.component;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.api_platform.action.ResponseToAPIRequestAction;
import com.bytechef.component.api_platform.trigger.ApiPlatformNewAPIRequestTrigger;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class ApiPlatformComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component(API_PLATFORM)
        .title("API Platform")
        .description("Actions and triggers for using with API platform.")
        .icon("path:assets/api-platform.svg")
        .categories(ComponentCategory.HELPERS)
        .triggers(ApiPlatformNewAPIRequestTrigger.TRIGGER_DEFINITION)
        .actions(ResponseToAPIRequestAction.ACTION_DEFINITION);

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
