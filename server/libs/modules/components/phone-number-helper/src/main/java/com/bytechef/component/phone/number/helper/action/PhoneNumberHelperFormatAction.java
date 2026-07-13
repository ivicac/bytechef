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
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.DEFAULT_REGION;
import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.FORMAT;
import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.PHONE_NUMBER;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * @author Ivica Cardic
 */
public class PhoneNumberHelperFormatAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("format")
        .title("Format")
        .description("Formats a phone number into the selected format.")
        .properties(
            string(PHONE_NUMBER)
                .label("Phone Number")
                .description("The phone number to format.")
                .required(true),
            string(DEFAULT_REGION)
                .label("Default Region")
                .description(
                    "The two-letter region code (e.g. US, DE) used when the phone number has no country code.")
                .required(false),
            string(FORMAT)
                .label("Format")
                .description("The format to apply to the phone number.")
                .options(
                    option("E.164", "E164"),
                    option("International", "INTERNATIONAL"),
                    option("National", "NATIONAL"),
                    option("RFC3966", "RFC3966"))
                .defaultValue("E164")
                .required(false))
        .output(
            outputSchema(
                string()
                    .description("The formatted phone number.")))
        .help("", "https://docs.bytechef.io/reference/components/phone-number-helper_v1#format")
        .perform(PhoneNumberHelperFormatAction::perform);

    private PhoneNumberHelperFormatAction() {
    }

    public static String perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) throws NumberParseException {

        PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

        PhoneNumber phoneNumber = phoneNumberUtil.parse(
            inputParameters.getRequiredString(PHONE_NUMBER), inputParameters.getString(DEFAULT_REGION));

        PhoneNumberFormat phoneNumberFormat = PhoneNumberFormat.valueOf(
            inputParameters.getString(FORMAT, "E164"));

        return phoneNumberUtil.format(phoneNumber, phoneNumberFormat);
    }
}
