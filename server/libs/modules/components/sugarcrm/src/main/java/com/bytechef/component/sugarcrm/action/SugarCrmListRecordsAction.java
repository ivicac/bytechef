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

package com.bytechef.component.sugarcrm.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.sugarcrm.constant.SugarCrmConstants.MAX_NUM;
import static com.bytechef.component.sugarcrm.constant.SugarCrmConstants.MODULE;
import static com.bytechef.component.sugarcrm.constant.SugarCrmConstants.PASSWORD;
import static com.bytechef.component.sugarcrm.constant.SugarCrmConstants.PLATFORM;
import static com.bytechef.component.sugarcrm.constant.SugarCrmConstants.USERNAME;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class SugarCrmListRecordsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listRecords")
        .title("List Records")
        .description("Lists records of the given module.")
        .properties(
            string(MODULE)
                .label("Module")
                .description("The name of the SugarCRM module (e.g. Accounts, Contacts, Leads).")
                .required(true),
            integer(MAX_NUM)
                .label("Maximum Records")
                .description("The maximum number of records to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("next_offset")
                            .description("The offset for the next page of results."),
                        array("records")
                            .description("The records of the module.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the record."),
                                        string("name")
                                            .description("The name of the record."))))))
        .help("", "https://docs.bytechef.io/reference/components/sugarCrm_v1#list-records")
        .perform(SugarCrmListRecordsAction::perform);

    private SugarCrmListRecordsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Object tokenResult = context.http(http -> http.post("/oauth2/token"))
            .body(
                Body.of(
                    Map.of(
                        "grant_type", "password",
                        "client_id", "sugar",
                        "client_secret", "",
                        "username", connectionParameters.getRequiredString(USERNAME),
                        "password", connectionParameters.getRequiredString(PASSWORD),
                        "platform", connectionParameters.getString(PLATFORM, "base"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();

        Object accessToken = ((Map<?, ?>) tokenResult).get("access_token");

        return context.http(http -> http.get("/" + inputParameters.getRequiredString(MODULE)))
            .header("OAuth-Token", String.valueOf(accessToken))
            .queryParameters("max_num", inputParameters.getInteger(MAX_NUM))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
