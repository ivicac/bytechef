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

package com.bytechef.component.openweather.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.openweather.constant.OpenWeatherConstants.CITY;
import static com.bytechef.component.openweather.constant.OpenWeatherConstants.LANG;
import static com.bytechef.component.openweather.constant.OpenWeatherConstants.UNITS;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class OpenWeatherGetCurrentWeatherAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getCurrentWeather")
        .title("Get Current Weather")
        .description("Retrieves the current weather for the specified city.")
        .properties(
            string(CITY)
                .label("City")
                .description("The city name, optionally followed by a state code and country code (e.g. London,GB).")
                .required(true),
            string(UNITS)
                .label("Units")
                .description("The unit system used for the weather values.")
                .options(
                    option("Standard (Kelvin)", "standard"),
                    option("Metric (Celsius)", "metric"),
                    option("Imperial (Fahrenheit)", "imperial"))
                .defaultValue("metric")
                .required(false),
            string(LANG)
                .label("Language")
                .description("The language of the weather description (e.g. en, de, fr).")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("coord")
                            .description("The geographical coordinates of the location.")
                            .properties(
                                number("lon")
                                    .description("The longitude of the location."),
                                number("lat")
                                    .description("The latitude of the location.")),
                        array("weather")
                            .description("The weather conditions.")
                            .items(
                                object()
                                    .properties(
                                        integer("id")
                                            .description("The weather condition id."),
                                        string("main")
                                            .description("The group of weather parameters."),
                                        string("description")
                                            .description("The weather condition within the group."),
                                        string("icon")
                                            .description("The weather icon id."))),
                        object("main")
                            .description("The main weather measurements.")
                            .properties(
                                number("temp")
                                    .description("The temperature."),
                                number("feels_like")
                                    .description("The human perception of the temperature."),
                                number("temp_min")
                                    .description("The minimum temperature at the moment."),
                                number("temp_max")
                                    .description("The maximum temperature at the moment."),
                                integer("pressure")
                                    .description("The atmospheric pressure in hPa."),
                                integer("humidity")
                                    .description("The humidity in percent.")),
                        object("wind")
                            .description("The wind measurements.")
                            .properties(
                                number("speed")
                                    .description("The wind speed."),
                                integer("deg")
                                    .description("The wind direction in degrees."),
                                number("gust")
                                    .description("The wind gust.")),
                        object("clouds")
                            .description("The cloudiness.")
                            .properties(
                                integer("all")
                                    .description("The cloudiness in percent.")),
                        integer("dt")
                            .description("The time of data calculation, unix, UTC."),
                        object("sys")
                            .description("Additional system information.")
                            .properties(
                                string("country")
                                    .description("The country code."),
                                integer("sunrise")
                                    .description("The sunrise time, unix, UTC."),
                                integer("sunset")
                                    .description("The sunset time, unix, UTC.")),
                        integer("timezone")
                            .description("The shift in seconds from UTC."),
                        integer("id")
                            .description("The city id."),
                        string("name")
                            .description("The city name."))))
        .help("", "https://docs.bytechef.io/reference/components/openweather_v1#get-current-weather")
        .perform(OpenWeatherGetCurrentWeatherAction::perform);

    private OpenWeatherGetCurrentWeatherAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/weather"))
            .queryParameters(
                CITY, inputParameters.getRequiredString(CITY),
                UNITS, inputParameters.getString(UNITS),
                LANG, inputParameters.getString(LANG))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
