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

package com.bytechef.cli.command.automation;

import com.bytechef.cli.client.automation.ApiClient;
import com.bytechef.cli.client.automation.ApiException;
import com.bytechef.cli.client.automation.api.ProjectCodeWorkflowApi;
import com.bytechef.cli.client.automation.api.ProjectGitApi;
import com.bytechef.cli.client.automation.api.WorkflowExecutionApi;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;
import com.bytechef.cli.core.config.CliConfig;
import com.bytechef.cli.core.error.CliException;
import com.bytechef.cli.core.http.AuthInterceptor;

/**
 * Builds automation public API clients configured with the resolved host and auth headers, and maps client
 * {@link ApiException}s onto {@link CliException}s carrying the right exit code.
 *
 * @author Ivica Cardic
 */
final class AutomationClientFactory {

    private static final String AUTOMATION_API_PATH = "/api/automation/v1";

    private AutomationClientFactory() {
    }

    static ApiClient apiClient(CliConfig config) {
        ApiClient apiClient = new ApiClient();

        apiClient.updateBaseUri(AuthInterceptor.baseUri(config, AUTOMATION_API_PATH));
        apiClient.setRequestInterceptor(new AuthInterceptor(config));

        return apiClient;
    }

    static WorkflowExecutionApi workflowExecutionApi(CliConfig config) {
        return new WorkflowExecutionApi(apiClient(config));
    }

    static ProjectCodeWorkflowApi projectCodeWorkflowApi(CliConfig config) {
        return new ProjectCodeWorkflowApi(apiClient(config));
    }

    static ProjectGitApi projectGitApi(CliConfig config) {
        return new ProjectGitApi(apiClient(config));
    }

    /**
     * The generated data-table client's {@code ApiClient} is a distinct type per generated package, so it needs its own
     * builder rather than an overload of {@link #apiClient(CliConfig)}.
     */
    static com.bytechef.cli.client.automationdatatable.ApiClient dataTableApiClient(CliConfig config) {
        com.bytechef.cli.client.automationdatatable.ApiClient apiClient =
            new com.bytechef.cli.client.automationdatatable.ApiClient();

        apiClient.updateBaseUri(AuthInterceptor.baseUri(config, AUTOMATION_API_PATH));
        apiClient.setRequestInterceptor(new AuthInterceptor(config));

        return apiClient;
    }

    static DataTableApi dataTableApi(CliConfig config) {
        return new DataTableApi(dataTableApiClient(config));
    }

    static DataTableRowApi dataTableRowApi(CliConfig config) {
        return new DataTableRowApi(dataTableApiClient(config));
    }

    static CliException toCliException(ApiException exception) {
        return toCliException(exception.getCode());
    }

    static CliException toCliException(com.bytechef.cli.client.automationdatatable.ApiException exception) {
        return toCliException(exception.getCode());
    }

    static CliException toCliException(int status) {
        if (status == 401 || status == 403) {
            return new CliException(2, "Authentication failed (HTTP " + status + ").");
        }

        if (status == 404) {
            return new CliException(3, "Not found (HTTP 404).");
        }

        return new CliException(1, "Request failed (HTTP " + status + ").");
    }
}
