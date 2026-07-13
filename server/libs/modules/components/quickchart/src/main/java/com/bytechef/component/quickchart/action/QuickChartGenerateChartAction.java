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

package com.bytechef.component.quickchart.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.fileEntry;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.quickchart.constant.QuickChartConstants.CHART;
import static com.bytechef.component.quickchart.constant.QuickChartConstants.HEIGHT;
import static com.bytechef.component.quickchart.constant.QuickChartConstants.WIDTH;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property.ControlType;

/**
 * @author Ivica Cardic
 */
public class QuickChartGenerateChartAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("generateChart")
        .title("Generate Chart")
        .description("Generates a chart image from a Chart.js configuration.")
        .properties(
            string(CHART)
                .label("Chart Configuration")
                .description("The Chart.js configuration of the chart as JSON.")
                .controlType(ControlType.TEXT_AREA)
                .required(true),
            integer(WIDTH)
                .label("Width")
                .description("The width of the chart image in pixels.")
                .required(false),
            integer(HEIGHT)
                .label("Height")
                .description("The height of the chart image in pixels.")
                .required(false))
        .output(outputSchema(fileEntry().description("The generated chart image.")))
        .help("", "https://docs.bytechef.io/reference/components/quickChart_v1#generate-chart")
        .perform(QuickChartGenerateChartAction::perform);

    private QuickChartGenerateChartAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("https://quickchart.io/chart"))
            .body(
                Body.of(
                    CHART, inputParameters.getRequiredString(CHART),
                    WIDTH, inputParameters.getInteger(WIDTH),
                    HEIGHT, inputParameters.getInteger(HEIGHT)))
            .configuration(responseType(ResponseType.BINARY))
            .execute()
            .getBody();
    }
}
