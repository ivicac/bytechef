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
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.totp.helper.constant.TotpHelperConstants.DIGITS;
import static com.bytechef.component.totp.helper.constant.TotpHelperConstants.PERIOD;
import static com.bytechef.component.totp.helper.constant.TotpHelperConstants.SECRET;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.totp.helper.util.TotpHelperUtils;
import java.time.Instant;

/**
 * @author Ivica Cardic
 */
public class TotpHelperGenerateCodeAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("generateCode")
        .title("Generate Code")
        .description("Generates the current time-based one-time password (TOTP) for the secret.")
        .properties(
            string(SECRET)
                .label("Secret")
                .description("The Base32 encoded shared secret.")
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
                string()
                    .description("The current TOTP code.")))
        .help("", "https://docs.bytechef.io/reference/components/totp-helper_v1#generate-code")
        .perform(TotpHelperGenerateCodeAction::perform);

    private TotpHelperGenerateCodeAction() {
    }

    public static String perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Instant now = Instant.now();

        return TotpHelperUtils.generateTotp(
            inputParameters.getRequiredString(SECRET), now.getEpochSecond(),
            inputParameters.getInteger(PERIOD, 30), inputParameters.getInteger(DIGITS, 6));
    }
}
