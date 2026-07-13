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

package com.bytechef.component.list.helper.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.list.helper.constant.ListHelperConstants.LIST;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import java.util.List;

/**
 * @author Ivica Cardic
 */
public class ListHelperCountAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("count")
        .title("Count")
        .description("Returns the number of items in the list.")
        .properties(
            array(LIST)
                .label("List")
                .description("The list whose items are counted.")
                .required(true))
        .output(
            outputSchema(
                integer()
                    .description("The number of items in the list.")))
        .help("", "https://docs.bytechef.io/reference/components/list-helper_v1#count")
        .perform(ListHelperCountAction::perform);

    private ListHelperCountAction() {
    }

    public static Integer perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        List<Object> list = inputParameters.getRequiredList(LIST, Object.class);

        return list.size();
    }
}
