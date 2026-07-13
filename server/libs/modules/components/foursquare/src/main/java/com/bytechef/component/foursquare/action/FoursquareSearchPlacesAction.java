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

package com.bytechef.component.foursquare.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.foursquare.constant.FoursquareConstants.LIMIT;
import static com.bytechef.component.foursquare.constant.FoursquareConstants.NEAR;
import static com.bytechef.component.foursquare.constant.FoursquareConstants.QUERY;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class FoursquareSearchPlacesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("searchPlaces")
        .title("Search Places")
        .description("Searches for places matching the query.")
        .properties(
            string(QUERY)
                .label("Query")
                .description("The search query (e.g. coffee, pizza).")
                .required(false),
            string(NEAR)
                .label("Near")
                .description("The locality where the search should be performed (e.g. Chicago, IL).")
                .required(false),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of places to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("results")
                            .description("The places matching the search.")
                            .items(
                                object()
                                    .properties(
                                        string("fsq_id")
                                            .description("The id of the place."),
                                        string("name")
                                            .description("The name of the place."),
                                        object("location")
                                            .description("The location of the place.")
                                            .properties(
                                                string("formatted_address")
                                                    .description("The formatted address of the place."),
                                                string("country")
                                                    .description("The country of the place.")),
                                        array("categories")
                                            .description("The categories of the place.")
                                            .items(
                                                object()
                                                    .properties(
                                                        integer("id")
                                                            .description("The id of the category."),
                                                        string("name")
                                                            .description("The name of the category."))))))))
        .help("", "https://docs.bytechef.io/reference/components/foursquare_v1#search-places")
        .perform(FoursquareSearchPlacesAction::perform);

    private FoursquareSearchPlacesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/places/search"))
            .queryParameters(
                QUERY, inputParameters.getString(QUERY),
                NEAR, inputParameters.getString(NEAR),
                LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
