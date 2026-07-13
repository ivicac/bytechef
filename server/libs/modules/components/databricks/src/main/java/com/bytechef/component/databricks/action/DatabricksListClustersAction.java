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

package com.bytechef.component.databricks.action;

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
public class DatabricksListClustersAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listClusters")
        .title("List Clusters")
        .description("Returns a list of clusters in the workspace.")
        .output(
            outputSchema(
                object()
                    .properties(
                        array("clusters")
                            .description("The clusters in the workspace.")
                            .items(
                                object()
                                    .properties(
                                        string("cluster_id")
                                            .description("The id of the cluster."),
                                        string("cluster_name")
                                            .description("The name of the cluster."),
                                        string("state")
                                            .description("The state of the cluster."))))))
        .help("", "https://docs.bytechef.io/reference/components/databricks_v1#list-clusters")
        .perform(DatabricksListClustersAction::perform);

    private DatabricksListClustersAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.get("/api/2.1/clusters/list"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}
