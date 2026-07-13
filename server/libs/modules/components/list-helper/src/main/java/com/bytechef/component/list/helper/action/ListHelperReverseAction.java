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
import java.util.Collections;
import java.util.List;

/**
 * @author Ivica Cardic
 */
public class ListHelperReverseAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("reverse")
        .title("Reverse")
        .description("Reverses the order of the items in the list.")
        .properties(
            array(LIST)
                .label("List")
                .description("The list to reverse.")
                .required(true))
        .output(
            outputSchema(
                array()
                    .description("The reversed list.")))
        .help("", "https://docs.bytechef.io/reference/components/list-helper_v1#reverse")
        .perform(ListHelperReverseAction::perform);

    private ListHelperReverseAction() {
    }

    public static List<Object> perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        List<Object> list = new ArrayList<>(inputParameters.getRequiredList(LIST, Object.class));

        Collections.reverse(list);

        return list;
    }
}
