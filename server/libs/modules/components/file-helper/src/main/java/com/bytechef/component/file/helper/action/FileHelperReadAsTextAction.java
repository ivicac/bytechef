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

/**
 * @author Ivica Cardic
 */
public class FileHelperReadAsTextAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("readAsText")
        .title("Read as Text")
        .description("Reads the content of the file as text.")
        .properties(
            fileEntry(FILE)
                .label("File")
                .description("The file to read.")
                .required(true))
        .output(
            outputSchema(
                string()
                    .description("The content of the file.")))
        .help("", "https://docs.bytechef.io/reference/components/file-helper_v1#read-as-text")
        .perform(FileHelperReadAsTextAction::perform);

    private FileHelperReadAsTextAction() {
    }

    public static String perform(Parameters inputParameters, Parameters connectionParameters, ActionContext context) {
        return context.file(file -> file.readToString(inputParameters.getRequiredFileEntry(FILE)));
    }
}
