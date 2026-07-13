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

package com.bytechef.component.microsoft.clarity.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.microsoft.clarity.constant.MicrosoftClarityConstants.DIMENSION_1;
import static com.bytechef.component.microsoft.clarity.constant.MicrosoftClarityConstants.NUM_OF_DAYS;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class MicrosoftClarityGetLiveInsightsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getLiveInsights")
        .title("Get Live Insights")
        .description("Retrieves project analytics such as traffic, engagement and popular pages.")
        .properties(
            integer(NUM_OF_DAYS)
                .label("Number of Days")
                .description("The number of days of data to retrieve, counting back from today.")
                .options(
                    option("1", 1),
                    option("2", 2),
                    option("3", 3))
                .defaultValue(1)
                .required(true),
            string(DIMENSION_1)
                .label("Dimension")
                .description("The dimension to break down the insights by.")
                .options(
                    option("Browser", "Browser"),
                    option("Device", "Device"),
                    option("Country", "Country"),
                    option("OS", "OS"),
                    option("Source", "Source"),
                    option("URL", "URL"))
                .required(false))
        .output(
            outputSchema(
                array()
                    .description("The project insights.")
                    .items(
                        object()
                            .properties(
                                string("metricName")
                                    .description("The name of the metric."),
                                array("information")
                                    .description("The values of the metric.")
                                    .items(object())))))
        .help("", "https://docs.bytechef.io/reference/components/microsoftClarity_v1#get-live-insights")
        .perform(MicrosoftClarityGetLiveInsightsAction::perform);

    private MicrosoftClarityGetLiveInsightsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/project-live-insights"))
            .queryParameters(
                NUM_OF_DAYS, inputParameters.getRequiredInteger(NUM_OF_DAYS),
                DIMENSION_1, inputParameters.getString(DIMENSION_1))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
