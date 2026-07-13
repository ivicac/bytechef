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

package com.bytechef.component.totp.helper.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.totp.helper.constant.TotpHelperConstants.CODE;
import static com.bytechef.component.totp.helper.constant.TotpHelperConstants.DIGITS;
import static com.bytechef.component.totp.helper.constant.TotpHelperConstants.PERIOD;
import static com.bytechef.component.totp.helper.constant.TotpHelperConstants.SECRET;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.totp.helper.util.TotpHelperUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * @author Ivica Cardic
 */
public class TotpHelperVerifyCodeAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("verifyCode")
        .title("Verify Code")
        .description(
            "Verifies a time-based one-time password (TOTP) against the secret, allowing one time step of clock " +
                "drift.")
        .properties(
            string(SECRET)
                .label("Secret")
                .description("The Base32 encoded shared secret.")
                .required(true),
            string(CODE)
                .label("Code")
                .description("The code to verify.")
                .required(true),
            integer(PERIOD)
                .label("Period")
                .description("The time step in seconds.")
                .defaultValue(30)
                .required(false),
            integer(DIGITS)
                .label("Digits")
                .description("The number of digits of the code.")
                .defaultValue(6)
                .required(false))
        .output(
            outputSchema(
                bool()
                    .description("Whether the code is valid.")))
        .help("", "https://docs.bytechef.io/reference/components/totp-helper_v1#verify-code")
        .perform(TotpHelperVerifyCodeAction::perform);

    private TotpHelperVerifyCodeAction() {
    }

    public static Boolean perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String secret = inputParameters.getRequiredString(SECRET);
        String code = inputParameters.getRequiredString(CODE);

        int period = inputParameters.getInteger(PERIOD, 30);
        int digits = inputParameters.getInteger(DIGITS, 6);

        Instant now = Instant.now();

        long epochSecond = now.getEpochSecond();

        for (int drift = -1; drift <= 1; drift++) {
            String expectedCode = TotpHelperUtils.generateTotp(secret, epochSecond + ((long) drift * period), period,
                digits);

            if (MessageDigest.isEqual(
                expectedCode.getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {

                return true;
            }
        }

        return false;
    }
}
