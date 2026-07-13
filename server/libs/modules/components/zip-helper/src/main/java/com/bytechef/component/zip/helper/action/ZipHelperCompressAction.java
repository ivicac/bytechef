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
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.zip.helper.constant.ZipHelperConstants.FILENAME;
import static com.bytechef.component.zip.helper.constant.ZipHelperConstants.FILES;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.component.definition.Parameters;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author Ivica Cardic
 */
public class ZipHelperCompressAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("compress")
        .title("Compress")
        .description("Compresses the given files into a zip file.")
        .properties(
            array(FILES)
                .label("Files")
                .description("The files to compress.")
                .items(fileEntry())
                .required(true),
            string(FILENAME)
                .label("Filename")
                .description("Filename to set for the zip file. By default, \"file.zip\" will be used.")
                .defaultValue("file.zip")
                .advancedOption(true))
        .output(outputSchema(fileEntry().description("The zip file containing the compressed files.")))
        .help("", "https://docs.bytechef.io/reference/components/zip-helper_v1#compress")
        .perform(ZipHelperCompressAction::perform);

    private ZipHelperCompressAction() {
    }

    public static FileEntry perform(
        Parameters inputParameters, Parameters connectionParameters, ActionContext context) throws IOException {

        List<FileEntry> fileEntries = inputParameters.getFileEntries(FILES, List.of());

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
            for (FileEntry fileEntry : fileEntries) {
                zipOutputStream.putNextEntry(new ZipEntry(fileEntry.getName()));

                byte[] bytes = context.file(file -> file.readAllBytes(fileEntry));

                zipOutputStream.write(bytes);
                zipOutputStream.closeEntry();
            }
        }

        try (InputStream inputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray())) {
            return context.file(
                file -> file.storeContent(inputParameters.getString(FILENAME, "file.zip"), inputStream));
        }
    }
}
