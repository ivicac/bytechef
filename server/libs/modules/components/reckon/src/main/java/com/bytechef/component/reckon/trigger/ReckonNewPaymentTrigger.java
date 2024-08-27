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

package com.bytechef.component.reckon.trigger;

import static com.bytechef.component.definition.ComponentDSL.array;
import static com.bytechef.component.definition.ComponentDSL.date;
import static com.bytechef.component.definition.ComponentDSL.number;
import static com.bytechef.component.definition.ComponentDSL.object;
import static com.bytechef.component.definition.ComponentDSL.string;
import static com.bytechef.component.definition.ComponentDSL.trigger;
import static com.bytechef.component.reckon.constant.ReckonConstants.BOOK_ID;
import static com.bytechef.component.reckon.constant.ReckonConstants.ID;
import static com.bytechef.component.reckon.constant.ReckonConstants.NAME;

import com.bytechef.component.definition.ComponentDSL.ModifiableTriggerDefinition;
import com.bytechef.component.definition.OptionsDataSource.TriggerOptionsFunction;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TriggerContext;
import com.bytechef.component.definition.TriggerDefinition.PollOutput;
import com.bytechef.component.definition.TriggerDefinition.TriggerType;
import com.bytechef.component.reckon.util.ReckonUtils;

/**
 * @author Monika Kušter
 */
public class ReckonNewPaymentTrigger {

    public static final ModifiableTriggerDefinition TRIGGER_DEFINITION = trigger("newPayment")
        .title("New Payment")
        .description("Triggers when a new payment is created.")
        .type(TriggerType.POLLING)
        .properties(
            string(BOOK_ID)
                .label("Book")
                .options((TriggerOptionsFunction<String>) ReckonUtils::getBookIdOptions)
                .required(true))
        .outputSchema(
            array()
                .items(
                    object()
                        .properties(
                            string(ID),
                            string("paymentNumber"),
                            object("supplier")
                                .properties(
                                    string(ID),
                                    string(NAME)),
                            date("paymentDate"),
                            number("totalAmount"))))
        .poll(ReckonNewPaymentTrigger::poll);

    private ReckonNewPaymentTrigger() {
    }

    protected static PollOutput poll(
        Parameters inputParameters, Parameters connectionParameters, Parameters closureParameters,
        TriggerContext context) {

        return ReckonUtils.getPollOutput(inputParameters, closureParameters, context, "payments");
    }
}