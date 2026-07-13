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
public class BufferGetAccountAction {

    private static final String GET_ACCOUNT_QUERY = """
        query GetAccount {
          account {
            id
            email
            name
            timezone
            organizations {
              id
              name
              channelCount
            }
          }
        }""";

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getAccount")
        .title("Get Account")
        .description("Returns the authenticated account and its organizations.")
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .properties(
                                object("account")
                                    .properties(
                                        string("id"),
                                        string("email"),
                                        string("name"),
                                        array("organizations")
                                            .items(
                                                object()
                                                    .properties(
                                                        string("id"),
                                                        string("name"))))))))
        .perform(BufferGetAccountAction::perform);

    private BufferGetAccountAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/"))
            .body(Body.of(Map.of("query", GET_ACCOUNT_QUERY)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
