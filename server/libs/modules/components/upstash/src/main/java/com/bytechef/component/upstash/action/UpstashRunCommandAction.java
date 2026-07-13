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

package com.bytechef.component.upstash.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.upstash.constant.UpstashConstants.COMMAND;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.List;

/**
 * @author Ivica Cardic
 */
public class UpstashRunCommandAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("runCommand")
        .title("Run Command")
        .description("Runs a Redis command against the Upstash database.")
        .properties(
            array(COMMAND)
                .label("Command")
                .description("The Redis command as a list of parts (e.g. [\"SET\", \"key\", \"value\"]).")
                .items(string())
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("result")
                            .description("The result of the command."))))
        .help("", "https://docs.bytechef.io/reference/components/upstash_v1#run-command")
        .perform(UpstashRunCommandAction::perform);

    private UpstashRunCommandAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        List<String> command = inputParameters.getRequiredList(COMMAND, String.class);

        return context.http(http -> http.post("/"))
            .body(Body.of(command))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
