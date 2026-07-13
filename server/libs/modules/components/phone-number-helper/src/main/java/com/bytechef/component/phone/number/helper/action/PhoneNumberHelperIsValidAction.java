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

package com.bytechef.component.phone.number.helper.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.DEFAULT_REGION;
import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.PHONE_NUMBER;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * @author Ivica Cardic
 */
public class PhoneNumberHelperIsValidAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("isValid")
        .title("Is Valid")
        .description("Checks if the phone number is valid.")
        .properties(
            string(PHONE_NUMBER)
                .label("Phone Number")
                .description("The phone number to validate.")
                .required(true),
            string(DEFAULT_REGION)
                .label("Default Region")
                .description(
                    "The two-letter region code (e.g. US, DE) used when the phone number has no country code.")
                .required(false))
        .output(
            outputSchema(
                bool()
                    .description("Whether the phone number is valid.")))
        .help("", "https://docs.bytechef.io/reference/components/phone-number-helper_v1#is-valid")
        .perform(PhoneNumberHelperIsValidAction::perform);

    private PhoneNumberHelperIsValidAction() {
    }

    public static Boolean perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

        try {
            PhoneNumber phoneNumber = phoneNumberUtil.parse(
                inputParameters.getRequiredString(PHONE_NUMBER), inputParameters.getString(DEFAULT_REGION));

            return phoneNumberUtil.isValidNumber(phoneNumber);
        } catch (NumberParseException numberParseException) {
            return false;
        }
    }
}
