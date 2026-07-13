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
import static com.bytechef.component.file.helper.constant.FileHelperConstants.FILENAME;
import static com.bytechef.component.file.helper.constant.FileHelperConstants.TEXT;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class FileHelperWriteTextAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("writeText")
        .title("Write Text to File")
        .description("Writes the text into a file.")
        .properties(
            string(TEXT)
                .label("Text")
                .description("The text to write into the file.")
                .required(true),
            string(FILENAME)
                .label("Filename")
                .description("Filename to set for the text file. By default, \"file.txt\" will be used.")
                .defaultValue("file.txt")
                .advancedOption(true))
        .output(outputSchema(fileEntry().description("The file containing the text.")))
        .help("", "https://docs.bytechef.io/reference/components/file-helper_v1#write-text-to-file")
        .perform(FileHelperWriteTextAction::perform);

    private FileHelperWriteTextAction() {
    }

    public static FileEntry perform(
        Parameters inputParameters, Parameters connectionParameters, ActionContext context) {

        return context.file(file -> file.storeContent(
            inputParameters.getString(FILENAME, "file.txt"), inputParameters.getRequiredString(TEXT)));
    }
}
