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

package com.bytechef.component.buffer.action;

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
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class BufferListChannelsAction {

    private static final String GET_CHANNELS_QUERY = """
        query GetChannels($input: ChannelsInput!) {
          channels(input: $input) {
            id
            name
            displayName
            service
            avatar
            timezone
            type
          }
        }""";

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listChannels")
        .title("List Channels")
        .description("Returns the social media channels connected to a Buffer organization.")
        .properties(
            string("organizationId")
                .label("Organization ID")
                .description("The ID of the Buffer organization. Use the Get Account action to find it.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .properties(
                                array("channels")
                                    .items(
                                        object()
                                            .properties(
                                                string("id"),
                                                string("name"),
                                                string("service")))))))
        .perform(BufferListChannelsAction::perform);

    private BufferListChannelsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/"))
            .body(
                Body.of(
                    Map.of(
                        "query", GET_CHANNELS_QUERY,
                        "variables", Map.of(
                            "input", Map.of(
                                "organizationId", inputParameters.getRequiredString("organizationId"))))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
