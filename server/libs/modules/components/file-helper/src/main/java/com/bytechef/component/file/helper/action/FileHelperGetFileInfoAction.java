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
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.file.helper.constant.FileHelperConstants.FILE;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.component.definition.Parameters;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class FileHelperGetFileInfoAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getFileInfo")
        .title("Get File Info")
        .description("Returns information about the file.")
        .properties(
            fileEntry(FILE)
                .label("File")
                .description("The file to get information about.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("name")
                            .description("The name of the file."),
                        string("extension")
                            .description("The extension of the file."),
                        string("mimeType")
                            .description("The mime type of the file."),
                        integer("size")
                            .description("The size of the file in bytes."))))
        .help("", "https://docs.bytechef.io/reference/components/file-helper_v1#get-file-info")
        .perform(FileHelperGetFileInfoAction::perform);

    private FileHelperGetFileInfoAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, ActionContext context) {

        FileEntry fileEntry = inputParameters.getRequiredFileEntry(FILE);

        Map<String, Object> result = new HashMap<>();

        result.put("name", fileEntry.getName());
        result.put("extension", fileEntry.getExtension());
        result.put("mimeType", fileEntry.getMimeType());
        result.put("size", context.file(file -> file.getContentLength(fileEntry)));

        return result;
    }
}
