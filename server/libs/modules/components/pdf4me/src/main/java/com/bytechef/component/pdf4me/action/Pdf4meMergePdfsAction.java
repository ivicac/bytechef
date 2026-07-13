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

package com.bytechef.component.pdf4me.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.fileEntry;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.component.definition.Parameters;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class Pdf4meMergePdfsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("mergePdfs")
        .title("Merge PDFs")
        .description("Merges two or more PDF files into a single PDF.")
        .properties(
            array("files")
                .label("Files")
                .description("The PDF files to merge, in order.")
                .items(fileEntry())
                .required(true),
            string("docName")
                .label("Document Name")
                .description("The name of the merged PDF file.")
                .defaultValue("merged.pdf")
                .required(false))
        .output(outputSchema(fileEntry()))
        .perform(Pdf4meMergePdfsAction::perform);

    private Pdf4meMergePdfsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Base64.Encoder encoder = Base64.getEncoder();

        List<String> docContent = inputParameters.getRequiredList("files", FileEntry.class)
            .stream()
            .map(fileEntry -> encoder.encodeToString(
                context.file(file -> file.readAllBytes(fileEntry))))
            .toList();

        String docName = inputParameters.getString("docName", "merged.pdf");

        return context.http(http -> http.post("/api/v2/Merge"))
            .body(
                Body.of(
                    Map.of(
                        "docContent", docContent,
                        "docName", docName)))
            .configuration(responseType(ResponseType.BINARY))
            .execute()
            .getBody();
    }
}
