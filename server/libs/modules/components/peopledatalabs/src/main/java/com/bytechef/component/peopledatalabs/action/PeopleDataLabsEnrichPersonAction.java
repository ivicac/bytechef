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

package com.bytechef.component.peopledatalabs.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.peopledatalabs.constant.PeopleDataLabsConstants.COMPANY;
import static com.bytechef.component.peopledatalabs.constant.PeopleDataLabsConstants.EMAIL;
import static com.bytechef.component.peopledatalabs.constant.PeopleDataLabsConstants.MIN_LIKELIHOOD;
import static com.bytechef.component.peopledatalabs.constant.PeopleDataLabsConstants.NAME;
import static com.bytechef.component.peopledatalabs.constant.PeopleDataLabsConstants.PROFILE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class PeopleDataLabsEnrichPersonAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("enrichPerson")
        .title("Enrich Person")
        .description(
            "Enriches a person with additional data. At least one of email, name, profile or company is required.")
        .properties(
            string(EMAIL)
                .label("Email")
                .description("The email address of the person.")
                .required(false),
            string(NAME)
                .label("Name")
                .description("The full name of the person.")
                .required(false),
            string(PROFILE)
                .label("Profile")
                .description("A social profile URL of the person (e.g. LinkedIn).")
                .required(false),
            string(COMPANY)
                .label("Company")
                .description("The name, website or social URL of a company the person has worked for.")
                .required(false),
            integer(MIN_LIKELIHOOD)
                .label("Minimum Likelihood")
                .description("The minimum likelihood score (0-10) a response must have to return data.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("status")
                            .description("The status of the enrichment."),
                        number("likelihood")
                            .description("The likelihood score of the match."),
                        object("data")
                            .description("The enriched person data.")
                            .properties(
                                string("full_name")
                                    .description("The full name of the person."),
                                string("job_title")
                                    .description("The job title of the person."),
                                string("job_company_name")
                                    .description("The company name of the person."),
                                string("linkedin_url")
                                    .description("The LinkedIn URL of the person."),
                                string("location_country")
                                    .description("The country of the person.")))))
        .help("", "https://docs.bytechef.io/reference/components/peopledatalabs_v1#enrich-person")
        .perform(PeopleDataLabsEnrichPersonAction::perform);

    private PeopleDataLabsEnrichPersonAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/person/enrich"))
            .queryParameters(
                EMAIL, inputParameters.getString(EMAIL),
                NAME, inputParameters.getString(NAME),
                PROFILE, inputParameters.getString(PROFILE),
                COMPANY, inputParameters.getString(COMPANY),
                MIN_LIKELIHOOD, inputParameters.getInteger(MIN_LIKELIHOOD))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
