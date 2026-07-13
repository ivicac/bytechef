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

import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.DEFAULT_REGION;
import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.FORMAT;
import static com.bytechef.component.phone.number.helper.constant.PhoneNumberHelperConstants.PHONE_NUMBER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.google.i18n.phonenumbers.NumberParseException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class PhoneNumberHelperActionTest {

    private final Context mockedContext = mock(Context.class);

    @Test
    void testPerformFormat() throws NumberParseException {
        Parameters parameters = MockParametersFactory.create(
            Map.of(PHONE_NUMBER, "202-555-0142", DEFAULT_REGION, "US", FORMAT, "E164"));

        assertEquals("+12025550142", PhoneNumberHelperFormatAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformIsValid() {
        Parameters parameters = MockParametersFactory.create(
            Map.of(PHONE_NUMBER, "+12025550142"));

        assertTrue(PhoneNumberHelperIsValidAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformIsValidInvalidNumber() {
        Parameters parameters = MockParametersFactory.create(Map.of(PHONE_NUMBER, "12345"));

        assertFalse(PhoneNumberHelperIsValidAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformParse() throws NumberParseException {
        Parameters parameters = MockParametersFactory.create(
            Map.of(PHONE_NUMBER, "+12025550142"));

        Map<String, Object> result = PhoneNumberHelperParseAction.perform(parameters, parameters, mockedContext);

        assertEquals(1, result.get("countryCode"));
        assertEquals("2025550142", result.get("nationalNumber"));
        assertEquals("+12025550142", result.get("e164"));
        assertEquals("US", result.get("region"));
        assertEquals(true, result.get("valid"));
    }
}
