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

package com.bytechef.component.backblaze.action;

import static com.bytechef.component.backblaze.constant.BackblazeConstants.APPLICATION_KEY;
import static com.bytechef.component.backblaze.constant.BackblazeConstants.KEY_ID;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class BackblazeListBucketsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listBuckets")
        .title("List Buckets")
        .description("Returns the buckets of the Backblaze B2 account.")
        .output(
            outputSchema(
                object()
                    .properties(
                        array("buckets")
                            .description("The buckets of the account.")
                            .items(
                                object()
                                    .properties(
                                        string("bucketId")
                                            .description("The id of the bucket."),
                                        string("bucketName")
                                            .description("The name of the bucket."),
                                        string("bucketType")
                                            .description("The type of the bucket."))))))
        .help("", "https://docs.bytechef.io/reference/components/backblaze_v1#list-buckets")
        .perform(BackblazeListBucketsAction::perform);

    private BackblazeListBucketsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Base64.Encoder encoder = Base64.getEncoder();

        String credentials = encoder.encodeToString(
            (connectionParameters.getRequiredString(KEY_ID) + ":" +
                connectionParameters.getRequiredString(APPLICATION_KEY)).getBytes(StandardCharsets.UTF_8));

        Object authorizeResult = context
            .http(http -> http.get("https://api.backblazeb2.com/b2api/v2/b2_authorize_account"))
            .header("Authorization", "Basic " + credentials)
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();

        Map<?, ?> authorizeMap = (Map<?, ?>) authorizeResult;

        Object apiUrl = authorizeMap.get("apiUrl");
        Object accountId = authorizeMap.get("accountId");
        Object authorizationToken = authorizeMap.get("authorizationToken");

        return context.http(http -> http.post(apiUrl + "/b2api/v2/b2_list_buckets"))
            .header("Authorization", String.valueOf(authorizationToken))
            .body(Body.of(Map.of("accountId", String.valueOf(accountId))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
