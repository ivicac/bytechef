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

package com.bytechef.component.agencyzoom.action;

import static com.bytechef.component.agencyzoom.constant.AgencyZoomConstants.PASSWORD;
import static com.bytechef.component.agencyzoom.constant.AgencyZoomConstants.USERNAME;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
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
public class AgencyZoomGetLeadsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getLeads")
        .title("Get Leads")
        .description("Returns the leads of the agency.")
        .output(
            outputSchema(
                object()
                    .properties(
                        array("leads")
                            .description("The leads of the agency.")
                            .items(
                                object()
                                    .properties(
                                        integer("id")
                                            .description("The id of the lead."),
                                        string("firstName")
                                            .description("The first name of the lead."),
                                        string("lastName")
                                            .description("The last name of the lead."),
                                        string("email")
                                            .description("The email address of the lead."))))))
        .help("", "https://docs.bytechef.io/reference/components/agencyZoom_v1#get-leads")
        .perform(AgencyZoomGetLeadsAction::perform);

    private AgencyZoomGetLeadsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Object loginResult = context.http(http -> http.post("/auth/login"))
            .body(
                Body.of(
                    Map.of(
                        "username", connectionParameters.getRequiredString(USERNAME),
                        "password", connectionParameters.getRequiredString(PASSWORD))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();

        Object jwtToken = ((Map<?, ?>) loginResult).get("jwtToken");

        return context.http(http -> http.get("/leads"))
            .header("Authorization", "Bearer " + jwtToken)
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
