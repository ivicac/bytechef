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

package com.bytechef.component.infisical.action;

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
public class InfisicalListSecretsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listSecrets")
        .title("List Secrets")
        .description("Returns the secrets in a project environment.")
        .properties(
            string("projectId")
                .label("Project ID")
                .description("The ID of the Infisical project.")
                .required(true),
            string("environment")
                .label("Environment")
                .description("The environment slug, e.g. dev, staging, prod.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("secrets")
                            .items(
                                object()
                                    .properties(
                                        string("secretKey"),
                                        string("secretValue"))))))
        .perform(InfisicalListSecretsAction::perform);

    private InfisicalListSecretsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/secrets"))
            .queryParameters(
                "projectId", inputParameters.getRequiredString("projectId"),
                "environment", inputParameters.getRequiredString("environment"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
