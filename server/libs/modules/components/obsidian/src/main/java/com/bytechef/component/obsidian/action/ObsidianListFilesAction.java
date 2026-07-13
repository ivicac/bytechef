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

package com.bytechef.component.obsidian.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class ObsidianListFilesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listFiles")
        .title("List Files")
        .description("Returns the files and directories in the vault or a directory.")
        .properties(
            string("path")
                .label("Directory Path")
                .description("The vault-relative directory path. Leave empty for the vault root.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("files")
                            .items(string()))))
        .perform(ObsidianListFilesAction::perform);

    private ObsidianListFilesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String path = inputParameters.getString("path");
        String url = path == null || path.isEmpty() ? "/vault/" : "/vault/%s/".formatted(path);

        return context.http(http -> http.get(url))
            .header("Accept", "application/json")
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
