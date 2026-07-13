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

package com.bytechef.component.zip.helper.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.fileEntry;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.zip.helper.constant.ZipHelperConstants.FILE;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.component.definition.Parameters;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author Ivica Cardic
 */
public class ZipHelperExtractAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("extract")
        .title("Extract")
        .description("Extracts the files from a zip file.")
        .properties(
            fileEntry(FILE)
                .label("File")
                .description("The zip file to extract.")
                .required(true))
        .output(
            outputSchema(
                array()
                    .description("The extracted files.")
                    .items(fileEntry())))
        .help("", "https://docs.bytechef.io/reference/components/zip-helper_v1#extract")
        .perform(ZipHelperExtractAction::perform);

    private ZipHelperExtractAction() {
    }

    public static List<FileEntry> perform(
        Parameters inputParameters, Parameters connectionParameters, ActionContext context) throws IOException {

        byte[] bytes = context.file(file -> file.readAllBytes(inputParameters.getRequiredFileEntry(FILE)));

        List<FileEntry> fileEntries = new ArrayList<>();

        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry zipEntry = zipInputStream.getNextEntry();

            while (zipEntry != null) {
                if (!zipEntry.isDirectory()) {
                    String name = zipEntry.getName();

                    byte[] entryBytes = zipInputStream.readAllBytes();

                    try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(entryBytes)) {
                        fileEntries.add(context.file(file -> file.storeContent(name, byteArrayInputStream)));
                    }
                }

                zipInputStream.closeEntry();

                zipEntry = zipInputStream.getNextEntry();
            }
        }

        return fileEntries;
    }
}
