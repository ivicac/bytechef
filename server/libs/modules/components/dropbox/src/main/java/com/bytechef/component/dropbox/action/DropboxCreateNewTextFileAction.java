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

package com.bytechef.component.dropbox.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.dropbox.constant.DropboxConstants.AUTORENAME;
import static com.bytechef.component.dropbox.constant.DropboxConstants.FILENAME;
import static com.bytechef.component.dropbox.constant.DropboxConstants.MUTE;
import static com.bytechef.component.dropbox.constant.DropboxConstants.PATH;
import static com.bytechef.component.dropbox.constant.DropboxConstants.STRICT_CONFLICT;
import static com.bytechef.component.dropbox.constant.DropboxConstants.TEXT;
import static com.bytechef.component.dropbox.util.DropboxUtils.uploadFile;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property.ControlType;

/**
 * @author Mario Cvjetojevic
 * @author Monika Kušter
 */
public class DropboxCreateNewTextFileAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createTextFile")
        .title("Create New Paper File")
        .description("Create a new .paper file on which you can write at a given path")
        .properties(
            string(PATH)
                .label("Path")
                .description("The path of the new paper file. Root is /.")
                .required(true),
            string(FILENAME)
                .label("Filename")
                .description("Name of the paper file")
                .required(true),
            string(TEXT)
                .label("Text")
                .description("The text to write into the file.")
                .controlType(ControlType.TEXT_AREA)
                .required(true),
            bool(AUTORENAME)
                .label("Auto Rename")
                .description(
                    "If there's a conflict, as determined by mode, have the Dropbox server try to autorename the " +
                        "file to avoid conflict.")
                .defaultValue(false)
                .required(false),
            bool(MUTE)
                .label("Mute")
                .description(
                    "Normally, users are made aware of any file modifications in their Dropbox account via " +
                        "notifications in the client software. If true, this tells the clients that this " +
                        "modification shouldn't result in a user notification.")
                .defaultValue(false)
                .required(false),
            bool(STRICT_CONFLICT)
                .label("Strict Conflict")
                .description(
                    "Be more strict about how each WriteMode detects conflict. For example, always return a " +
                        "conflict error when mode = WriteMode.update and the given \"rev\" doesn't match the " +
                        "existing file's \"rev\", even if the existing file has been deleted.")
                .defaultValue(false)
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("name")
                            .description(
                                "The name of the newly created file, including its extension. This is the last " +
                                    "component of the path."),
                        string("path_lower")
                            .description("The full path to the file in lowercase, as stored in the user's Dropbox."),
                        string("path_display")
                            .description(
                                "The display-friendly version of the file's path, preserving original casing for " +
                                    "readability."),
                        string("id")
                            .description("ID of the file within Dropbox."),
                        integer("size")
                            .description(
                                "The size of the file in bytes, representing the total amount of data it contains."),
                        bool("is_downloadable")
                            .description("Indicates whether the file can be directly downloaded from Dropbox."),
                        string("content_hash")
                            .description(
                                "A hash value representing the content of the file, used for verifying data integrity."))))
        .perform(DropboxCreateNewTextFileAction::perform);

    private DropboxCreateNewTextFileAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        FileEntry fileEntry = context.file(
            file -> file.storeContent(
                inputParameters.getRequiredString(FILENAME) + ".paper",
                inputParameters.getRequiredString(TEXT)));

        return uploadFile(inputParameters, context, fileEntry);
    }
}
