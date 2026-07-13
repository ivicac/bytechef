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

package com.bytechef.component.google.ads.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.google.ads.connection.GoogleAdsConnection.DEVELOPER_TOKEN;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property.ControlType;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class GoogleAdsSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("search")
        .title("Search")
        .description("Runs a Google Ads Query Language (GAQL) query against a customer account.")
        .properties(
            string("customerId")
                .label("Customer ID")
                .description("The numeric ID of the Google Ads customer account, without dashes.")
                .required(true),
            string("query")
                .label("Query")
                .description("The GAQL query, e.g. SELECT campaign.id, campaign.name FROM campaign.")
                .controlType(ControlType.TEXT_AREA)
                .required(true),
            string("loginCustomerId")
                .label("Login Customer ID")
                .description("The manager account ID when accessing a client account through a manager.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("results")
                            .items(object()))))
        .perform(GoogleAdsSearchAction::perform);

    private GoogleAdsSearchAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Context.Http.Executor executor = context
            .http(http -> http.post(
                "/customers/%s/googleAds:search".formatted(inputParameters.getRequiredString("customerId"))))
            .header("developer-token", connectionParameters.getRequiredString(DEVELOPER_TOKEN));

        if (inputParameters.getString("loginCustomerId") != null) {
            executor.header("login-customer-id", inputParameters.getString("loginCustomerId"));
        }

        return executor
            .body(Body.of(Map.of("query", inputParameters.getRequiredString("query"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
