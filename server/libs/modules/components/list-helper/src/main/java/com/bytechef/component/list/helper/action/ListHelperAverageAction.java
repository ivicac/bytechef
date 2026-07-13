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
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.list.helper.constant.ListHelperConstants.LIST;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import java.util.List;

/**
 * @author Ivica Cardic
 */
public class ListHelperAverageAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("average")
        .title("Average")
        .description("Returns the average of the numbers in the list.")
        .properties(
            array(LIST)
                .label("List")
                .description("The list of numbers.")
                .items(number())
                .required(true))
        .output(
            outputSchema(
                number()
                    .description("The average of the numbers in the list.")))
        .help("", "https://docs.bytechef.io/reference/components/list-helper_v1#average")
        .perform(ListHelperAverageAction::perform);

    private ListHelperAverageAction() {
    }

    public static Double perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        List<Number> numbers = inputParameters.getRequiredList(LIST, Number.class);

        if (numbers.isEmpty()) {
            return 0.0;
        }

        double sum = 0;

        for (Number number : numbers) {
            sum += number.doubleValue();
        }

        return sum / numbers.size();
    }
}
