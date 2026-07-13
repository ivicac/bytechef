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

package com.bytechef.component.sentry.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.sentry.constant.SentryConstants.ORGANIZATION_SLUG;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class SentryListProjectsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listProjects")
        .title("List Projects")
        .description("Lists the projects of an organization.")
        .properties(
            string(ORGANIZATION_SLUG)
                .label("Organization Slug")
                .description("The slug of the organization whose projects will be listed.")
                .required(true))
        .output(
            outputSchema(
                array()
                    .description("The projects of the organization.")
                    .items(
                        object()
                            .properties(
                                string("id")
                                    .description("The id of the project."),
                                string("slug")
                                    .description("The slug of the project."),
                                string("name")
                                    .description("The name of the project."),
                                string("platform")
                                    .description("The platform of the project."),
                                bool("isBookmarked")
                                    .description("Whether the project is bookmarked."),
                                string("dateCreated")
                                    .description("The date and time the project was created.")))))
        .help("", "https://docs.bytechef.io/reference/components/sentry_v1#list-projects")
        .perform(SentryListProjectsAction::perform);

    private SentryListProjectsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get(
                "/organizations/%s/projects/".formatted(inputParameters.getRequiredString(ORGANIZATION_SLUG))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
