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

package com.bytechef.component.lemlist.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.lemlist.constant.LemlistConstants.CAMPAIGN_ID;
import static com.bytechef.component.lemlist.constant.LemlistConstants.EMAIL;
import static com.bytechef.component.lemlist.constant.LemlistConstants.FIRST_NAME;
import static com.bytechef.component.lemlist.constant.LemlistConstants.LAST_NAME;

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
public class LemlistAddLeadToCampaignAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("addLeadToCampaign")
        .title("Add Lead to Campaign")
        .description("Adds a lead to a campaign.")
        .properties(
            string(CAMPAIGN_ID)
                .label("Campaign ID")
                .description("The id of the campaign the lead will be added to.")
                .required(true),
            string(EMAIL)
                .label("Email")
                .description("The email address of the lead.")
                .required(true),
            string(FIRST_NAME)
                .label("First Name")
                .description("The first name of the lead.")
                .required(false),
            string(LAST_NAME)
                .label("Last Name")
                .description("The last name of the lead.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("_id")
                            .description("The id of the created lead."),
                        string("email")
                            .description("The email address of the lead."),
                        string("campaignId")
                            .description("The id of the campaign the lead was added to."))))
        .help("", "https://docs.bytechef.io/reference/components/lemlist_v1#add-lead-to-campaign")
        .perform(LemlistAddLeadToCampaignAction::perform);

    private LemlistAddLeadToCampaignAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        String firstName = inputParameters.getString(FIRST_NAME);

        if (firstName != null) {
            body.put(FIRST_NAME, firstName);
        }

        String lastName = inputParameters.getString(LAST_NAME);

        if (lastName != null) {
            body.put(LAST_NAME, lastName);
        }

        return context
            .http(http -> http.post(
                "/campaigns/%s/leads/%s".formatted(
                    inputParameters.getRequiredString(CAMPAIGN_ID), inputParameters.getRequiredString(EMAIL))))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
