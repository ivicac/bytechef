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

package com.bytechef.component.google.analytics.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.google.analytics.constant.GoogleAnalyticsConstants.DIMENSIONS;
import static com.bytechef.component.google.analytics.constant.GoogleAnalyticsConstants.END_DATE;
import static com.bytechef.component.google.analytics.constant.GoogleAnalyticsConstants.METRICS;
import static com.bytechef.component.google.analytics.constant.GoogleAnalyticsConstants.PROPERTY_ID;
import static com.bytechef.component.google.analytics.constant.GoogleAnalyticsConstants.START_DATE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class GoogleAnalyticsRunReportAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("runReport")
        .title("Run Report")
        .description("Runs a customized report of your Google Analytics 4 event data.")
        .properties(
            string(PROPERTY_ID)
                .label("Property ID")
                .description("The numeric id of the Google Analytics 4 property.")
                .required(true),
            string(START_DATE)
                .label("Start Date")
                .description("The start date of the report (e.g. 2026-01-01, 7daysAgo, yesterday).")
                .required(true),
            string(END_DATE)
                .label("End Date")
                .description("The end date of the report (e.g. 2026-01-31, today).")
                .required(true),
            string(METRICS)
                .label("Metrics")
                .description("A comma-separated list of metrics (e.g. activeUsers,sessions).")
                .required(true),
            string(DIMENSIONS)
                .label("Dimensions")
                .description("A comma-separated list of dimensions (e.g. country,city).")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("dimensionHeaders")
                            .description("The headers of the dimensions.")
                            .items(
                                object()
                                    .properties(
                                        string("name")
                                            .description("The name of the dimension."))),
                        array("metricHeaders")
                            .description("The headers of the metrics.")
                            .items(
                                object()
                                    .properties(
                                        string("name")
                                            .description("The name of the metric."),
                                        string("type")
                                            .description("The type of the metric."))),
                        array("rows")
                            .description("The rows of the report.")
                            .items(
                                object()
                                    .properties(
                                        array("dimensionValues")
                                            .description("The dimension values of the row.")
                                            .items(
                                                object()
                                                    .properties(
                                                        string("value")
                                                            .description("The value of the dimension."))),
                                        array("metricValues")
                                            .description("The metric values of the row.")
                                            .items(
                                                object()
                                                    .properties(
                                                        string("value")
                                                            .description("The value of the metric."))))),
                        integer("rowCount")
                            .description("The total number of rows."))))
        .help("", "https://docs.bytechef.io/reference/components/googleAnalytics_v1#run-report")
        .perform(GoogleAnalyticsRunReportAction::perform);

    private GoogleAnalyticsRunReportAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(
            "dateRanges",
            List.of(
                Map.of(
                    START_DATE, inputParameters.getRequiredString(START_DATE),
                    END_DATE, inputParameters.getRequiredString(END_DATE))));
        body.put(METRICS, toNameObjects(inputParameters.getRequiredString(METRICS)));

        String dimensions = inputParameters.getString(DIMENSIONS);

        if (dimensions != null) {
            body.put(DIMENSIONS, toNameObjects(dimensions));
        }

        return context
            .http(http -> http.post(
                "/properties/%s:runReport".formatted(inputParameters.getRequiredString(PROPERTY_ID))))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }

    private static List<Map<String, String>> toNameObjects(String commaSeparatedNames) {
        return Arrays.stream(commaSeparatedNames.split(","))
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .map(name -> Map.of("name", name))
            .toList();
    }
}
