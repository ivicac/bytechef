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

package com.bytechef.component.file.helper.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.fileEntry;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.file.helper.constant.FileHelperConstants.FILE;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Parameters;
import java.util.Base64;

/**
 * @author Ivica Cardic
 */
public class FileHelperToBase64Action {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("toBase64")
        .title("File to Base64")
        .description("Encodes the content of the file into a Base64 string.")
        .properties(
            fileEntry(FILE)
                .label("File")
                .description("The file to encode.")
                .required(true))
        .output(
            outputSchema(
                string()
                    .description("The Base64 encoded content of the file.")))
        .help("", "https://docs.bytechef.io/reference/components/file-helper_v1#file-to-base64")
        .perform(FileHelperToBase64Action::perform);

    private FileHelperToBase64Action() {
    }

    public static String perform(Parameters inputParameters, Parameters connectionParameters, ActionContext context) {
        byte[] bytes = context.file(file -> file.readAllBytes(inputParameters.getRequiredFileEntry(FILE)));

        Base64.Encoder encoder = Base64.getEncoder();

        return encoder.encodeToString(bytes);
    }
}
