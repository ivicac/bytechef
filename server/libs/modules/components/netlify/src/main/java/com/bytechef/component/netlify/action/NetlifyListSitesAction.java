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

package com.bytechef.component.netlify.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class NetlifyListSitesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listSites")
        .title("List Sites")
        .description("Lists the sites of the account.")
        .output(
            outputSchema(
                array()
                    .description("The sites of the account.")
                    .items(
                        object()
                            .properties(
                                string("id")
                                    .description("The id of the site."),
                                string("name")
                                    .description("The name of the site."),
                                string("url")
                                    .description("The URL of the site."),
                                string("admin_url")
                                    .description("The Netlify admin URL of the site."),
                                string("state")
                                    .description("The state of the site.")))))
        .help("", "https://docs.bytechef.io/reference/components/netlify_v1#list-sites")
        .perform(NetlifyListSitesAction::perform);

    private NetlifyListSitesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/sites"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
