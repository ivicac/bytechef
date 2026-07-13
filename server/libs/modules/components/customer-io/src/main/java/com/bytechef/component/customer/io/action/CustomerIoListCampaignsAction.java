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

package com.bytechef.component.customer.io.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
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
public class CustomerIoListCampaignsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listCampaigns")
        .title("List Campaigns")
        .description("Returns a list of campaigns in the workspace.")
        .output(
            outputSchema(
                object()
                    .properties(
                        array("campaigns")
                            .description("The campaigns in the workspace.")
                            .items(
                                object()
                                    .properties(
                                        integer("id")
                                            .description("The id of the campaign."),
                                        string("name")
                                            .description("The name of the campaign."),
                                        string("state")
                                            .description("The state of the campaign."),
                                        integer("created")
                                            .description("The timestamp the campaign was created."),
                                        integer("updated")
                                            .description("The timestamp the campaign was updated."))))))
        .help("", "https://docs.bytechef.io/reference/components/customerIo_v1#list-campaigns")
        .perform(CustomerIoListCampaignsAction::perform);

    private CustomerIoListCampaignsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get("/campaigns"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
