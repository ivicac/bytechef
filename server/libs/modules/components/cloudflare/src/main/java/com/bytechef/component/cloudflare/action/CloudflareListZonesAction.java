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

package com.bytechef.component.cloudflare.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.bool;
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
public class CloudflareListZonesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listZones")
        .title("List Zones")
        .description("Lists the zones of the account.")
        .output(
            outputSchema(
                object()
                    .properties(
                        bool("success")
                            .description("Whether the request was successful."),
                        array("result")
                            .description("The zones of the account.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the zone."),
                                        string("name")
                                            .description("The domain name of the zone."),
                                        string("status")
                                            .description("The status of the zone."),
                                        array("name_servers")
                                            .description("The name servers assigned to the zone.")
                                            .items(string()))))))
        .help("", "https://docs.bytechef.io/reference/components/cloudflare_v1#list-zones")
        .perform(CloudflareListZonesAction::perform);

    private CloudflareListZonesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/zones"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
