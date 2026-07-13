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

package com.bytechef.component.google.realtime.database.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.google.realtime.database.constant.GoogleRealtimeDatabaseConstants.DATABASE_URL;
import static com.bytechef.component.google.realtime.database.constant.GoogleRealtimeDatabaseConstants.PATH;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class GoogleRealtimeDatabaseGetDataAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getData")
        .title("Get Data")
        .description("Reads the data at the given path of the Realtime Database.")
        .properties(
            string(DATABASE_URL)
                .label("Database URL")
                .description(
                    "The URL of your Realtime Database (e.g. https://myproject-default-rtdb.firebaseio.com).")
                .required(true),
            string(PATH)
                .label("Path")
                .description("The path to the data to read (e.g. users/user1). If empty, the root is read.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .description("The data at the given path.")))
        .help("", "https://docs.bytechef.io/reference/components/googleRealtimeDatabase_v1#get-data")
        .perform(GoogleRealtimeDatabaseGetDataAction::perform);

    private GoogleRealtimeDatabaseGetDataAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String rawDatabaseUrl = inputParameters.getRequiredString(DATABASE_URL);

        String databaseUrl = rawDatabaseUrl.endsWith("/")
            ? rawDatabaseUrl.substring(0, rawDatabaseUrl.length() - 1)
            : rawDatabaseUrl;

        String path = inputParameters.getString(PATH, "");

        return context.http(http -> http.get("%s/%s.json".formatted(databaseUrl, path)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
