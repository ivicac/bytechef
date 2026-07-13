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

package com.bytechef.component.cloudconvert.action;

import static com.bytechef.component.cloudconvert.constant.CloudConvertConstants.INPUT_FORMAT;
import static com.bytechef.component.cloudconvert.constant.CloudConvertConstants.OUTPUT_FORMAT;
import static com.bytechef.component.cloudconvert.constant.CloudConvertConstants.URL;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class CloudConvertConvertFileAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("convertFile")
        .title("Convert File")
        .description(
            "Creates a job that imports a file from a URL, converts it to the given format and exports the result.")
        .properties(
            string(URL)
                .label("File URL")
                .description("The URL of the file that will be converted.")
                .required(true),
            string(OUTPUT_FORMAT)
                .label("Output Format")
                .description("The target format the file will be converted to (e.g. pdf, png, docx).")
                .required(true),
            string(INPUT_FORMAT)
                .label("Input Format")
                .description("The format of the input file. If not set, it is detected automatically.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The created job.")
                            .properties(
                                string("id")
                                    .description("The id of the created job."),
                                string("status")
                                    .description("The status of the created job."),
                                string("created_at")
                                    .description("The date and time the job was created."),
                                array("tasks")
                                    .description("The tasks of the created job.")
                                    .items(
                                        object()
                                            .properties(
                                                string("id")
                                                    .description("The id of the task."),
                                                string("name")
                                                    .description("The name of the task."),
                                                string("operation")
                                                    .description("The operation of the task."),
                                                string("status")
                                                    .description("The status of the task.")))))))
        .help("", "https://docs.bytechef.io/reference/components/cloudconvert_v1#convert-file")
        .perform(CloudConvertConvertFileAction::perform);

    private CloudConvertConvertFileAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> convertTask = new HashMap<>();

        convertTask.put("operation", "convert");
        convertTask.put("input", "import-file");
        convertTask.put(OUTPUT_FORMAT, inputParameters.getRequiredString(OUTPUT_FORMAT));

        String inputFormat = inputParameters.getString(INPUT_FORMAT);

        if (inputFormat != null) {
            convertTask.put(INPUT_FORMAT, inputFormat);
        }

        Map<String, Object> tasks = Map.of(
            "import-file", Map.of(
                "operation", "import/url",
                URL, inputParameters.getRequiredString(URL)),
            "convert-file", convertTask,
            "export-file", Map.of(
                "operation", "export/url",
                "input", "convert-file"));

        return context.http(http -> http.post("/jobs"))
            .body(Body.of(Map.of("tasks", tasks)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
