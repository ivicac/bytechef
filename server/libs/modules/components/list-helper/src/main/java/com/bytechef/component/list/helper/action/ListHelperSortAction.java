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
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.list.helper.constant.ListHelperConstants.LIST;
import static com.bytechef.component.list.helper.constant.ListHelperConstants.ORDER;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import java.util.Comparator;
import java.util.List;

/**
 * @author Ivica Cardic
 */
public class ListHelperSortAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sort")
        .title("Sort")
        .description("Sorts the items of the list in ascending or descending order.")
        .properties(
            array(LIST)
                .label("List")
                .description("The list to sort.")
                .required(true),
            string(ORDER)
                .label("Order")
                .description("The order to sort the list in.")
                .options(option("Ascending", "asc"), option("Descending", "desc"))
                .defaultValue("asc")
                .required(false))
        .output(
            outputSchema(
                array()
                    .description("The sorted list.")))
        .help("", "https://docs.bytechef.io/reference/components/list-helper_v1#sort")
        .perform(ListHelperSortAction::perform);

    private ListHelperSortAction() {
    }

    public static List<Object> perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        List<Object> list = inputParameters.getRequiredList(LIST, Object.class);

        boolean allNumbers = list.stream()
            .allMatch(item -> item instanceof Number);

        Comparator<Object> comparator;

        if (allNumbers) {
            comparator = Comparator.comparingDouble(item -> ((Number) item).doubleValue());
        } else {
            comparator = Comparator.comparing(String::valueOf);
        }

        if ("desc".equals(inputParameters.getString(ORDER, "asc"))) {
            comparator = comparator.reversed();
        }

        return list.stream()
            .sorted(comparator)
            .toList();
    }
}
