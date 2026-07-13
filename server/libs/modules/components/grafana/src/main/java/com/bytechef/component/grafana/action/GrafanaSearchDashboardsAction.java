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

package com.bytechef.component.grafana.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.grafana.constant.GrafanaConstants.LIMIT;
import static com.bytechef.component.grafana.constant.GrafanaConstants.QUERY;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class GrafanaSearchDashboardsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("searchDashboards")
        .title("Search Dashboards")
        .description("Searches dashboards and folders of the Grafana instance.")
        .properties(
            string(QUERY)
                .label("Query")
                .description("The search query to filter dashboards by title.")
                .required(false),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of dashboards to return.")
                .required(false))
        .output(
            outputSchema(
                array()
                    .description("The dashboards matching the query.")
                    .items(
                        object()
                            .properties(
                                integer("id")
                                    .description("The id of the dashboard."),
                                string("uid")
                                    .description("The uid of the dashboard."),
                                string("title")
                                    .description("The title of the dashboard."),
                                string("url")
                                    .description("The URL of the dashboard."),
                                string("type")
                                    .description("The type of the search result.")))))
        .help("", "https://docs.bytechef.io/reference/components/grafana_v1#search-dashboards")
        .perform(GrafanaSearchDashboardsAction::perform);

    private GrafanaSearchDashboardsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/search"))
            .queryParameters(
                QUERY, inputParameters.getString(QUERY),
                LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
