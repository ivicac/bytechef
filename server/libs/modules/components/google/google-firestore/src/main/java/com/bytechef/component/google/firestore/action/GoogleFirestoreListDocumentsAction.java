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

package com.bytechef.component.google.firestore.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.google.firestore.constant.GoogleFirestoreConstants.COLLECTION;
import static com.bytechef.component.google.firestore.constant.GoogleFirestoreConstants.PAGE_SIZE;
import static com.bytechef.component.google.firestore.constant.GoogleFirestoreConstants.PROJECT_ID;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class GoogleFirestoreListDocumentsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listDocuments")
        .title("List Documents")
        .description("Returns the documents of the collection.")
        .properties(
            string(PROJECT_ID)
                .label("Project Id")
                .description("The id of the Google Cloud project.")
                .required(true),
            string(COLLECTION)
                .label("Collection")
                .description("The id of the Firestore collection.")
                .required(true),
            integer(PAGE_SIZE)
                .label("Page Size")
                .description("The maximum number of documents to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("documents")
                            .description("The documents of the collection.")
                            .items(
                                object()
                                    .properties(
                                        string("name")
                                            .description("The resource name of the document."),
                                        object("fields")
                                            .description("The fields of the document."))))))
        .help("", "https://docs.bytechef.io/reference/components/googleFirestore_v1#list-documents")
        .perform(GoogleFirestoreListDocumentsAction::perform);

    private GoogleFirestoreListDocumentsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get(
                "/projects/%s/databases/(default)/documents/%s".formatted(
                    inputParameters.getRequiredString(PROJECT_ID), inputParameters.getRequiredString(COLLECTION))))
            .queryParameters(PAGE_SIZE, inputParameters.getInteger(PAGE_SIZE))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
