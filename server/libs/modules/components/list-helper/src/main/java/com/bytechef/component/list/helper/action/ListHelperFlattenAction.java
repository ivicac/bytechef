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
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.list.helper.constant.ListHelperConstants.LIST;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Ivica Cardic
 */
public class ListHelperFlattenAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("flatten")
        .title("Flatten")
        .description("Flattens nested lists into a single list.")
        .properties(
            array(LIST)
                .label("List")
                .description("The list of nested lists to flatten.")
                .required(true))
        .output(
            outputSchema(
                array()
                    .description("The flattened list.")))
        .help("", "https://docs.bytechef.io/reference/components/list-helper_v1#flatten")
        .perform(ListHelperFlattenAction::perform);

    private ListHelperFlattenAction() {
    }

    public static List<Object> perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        List<Object> list = inputParameters.getRequiredList(LIST, Object.class);

        List<Object> flattenedList = new ArrayList<>();

        flatten(list, flattenedList);

        return flattenedList;
    }

    private static void flatten(List<?> list, List<Object> flattenedList) {
        for (Object item : list) {
            if (item instanceof List<?> nestedList) {
                flatten(nestedList, flattenedList);
            } else {
                flattenedList.add(item);
            }
        }
    }
}
