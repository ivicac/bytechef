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
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.DEFAULT_REGION;
import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.PHONE_NUMBER;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class PhoneNumberHelperParseAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("parse")
        .title("Parse")
        .description("Parses a phone number and returns its parts and validity.")
        .properties(
            string(PHONE_NUMBER)
                .label("Phone Number")
                .description("The phone number to parse.")
                .required(true),
            string(DEFAULT_REGION)
                .label("Default Region")
                .description(
                    "The two-letter region code (e.g. US, DE) used when the phone number has no country code.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("countryCode")
                            .description("The country calling code of the phone number."),
                        string("nationalNumber")
                            .description("The national number of the phone number."),
                        string("e164")
                            .description("The phone number in E.164 format."),
                        string("region")
                            .description("The region of the phone number."),
                        string("type")
                            .description("The type of the phone number."),
                        bool("valid")
                            .description("Whether the phone number is valid."))))
        .help("", "https://docs.bytechef.io/reference/components/phone-number-helper_v1#parse")
        .perform(PhoneNumberHelperParseAction::perform);

    private PhoneNumberHelperParseAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) throws NumberParseException {

        PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

        PhoneNumber phoneNumber = phoneNumberUtil.parse(
            inputParameters.getRequiredString(PHONE_NUMBER), inputParameters.getString(DEFAULT_REGION));

        PhoneNumberType phoneNumberType = phoneNumberUtil.getNumberType(phoneNumber);

        Map<String, Object> result = new HashMap<>();

        result.put("countryCode", phoneNumber.getCountryCode());
        result.put("nationalNumber", String.valueOf(phoneNumber.getNationalNumber()));
        result.put("e164", phoneNumberUtil.format(phoneNumber, PhoneNumberFormat.E164));
        result.put("region", phoneNumberUtil.getRegionCodeForNumber(phoneNumber));
        result.put("type", phoneNumberType.name());
        result.put("valid", phoneNumberUtil.isValidNumber(phoneNumber));

        return result;
    }
}
