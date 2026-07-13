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

package com.bytechef.component.phone.number.helper;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.tool;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.phone.number.helper.action.PhoneNumberHelperFormatAction;
import com.bytechef.component.phone.number.helper.action.PhoneNumberHelperIsValidAction;
import com.bytechef.component.phone.number.helper.action.PhoneNumberHelperParseAction;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class PhoneNumberHelperComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = component("phoneNumberHelper")
        .title("Phone Number Helper")
        .version(1)
        .description("Helper component which parses, formats and validates phone numbers.")
        .icon("path:assets/phone-number-helper.svg")
        .categories(ComponentCategory.HELPERS)
        .actions(
            PhoneNumberHelperFormatAction.ACTION_DEFINITION,
            PhoneNumberHelperIsValidAction.ACTION_DEFINITION,
            PhoneNumberHelperParseAction.ACTION_DEFINITION)
        .clusterElements(
            tool(PhoneNumberHelperFormatAction.ACTION_DEFINITION),
            tool(PhoneNumberHelperIsValidAction.ACTION_DEFINITION),
            tool(PhoneNumberHelperParseAction.ACTION_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }
}
