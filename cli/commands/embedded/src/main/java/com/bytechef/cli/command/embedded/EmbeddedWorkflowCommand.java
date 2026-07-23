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

package com.bytechef.cli.command.embedded;

import com.bytechef.cli.client.embeddedconfiguration.ApiException;
import com.bytechef.cli.client.embeddedconfiguration.api.ConnectedUserProjectWorkflowApi;
import com.bytechef.cli.client.embeddedconfiguration.model.PublishFrontendProjectWorkflowRequestModel;
import com.bytechef.cli.core.config.CliConfig;
import com.bytechef.cli.core.output.OutputRenderer;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;

/**
 * Commands for a connected user's projects and project workflows in the embedded product.
 *
 * @author Ivica Cardic
 */
@org.springframework.stereotype.Component
public class EmbeddedWorkflowCommand {

    private Path configPath = Path.of(System.getProperty("user.home"), ".bytechef", "config");
    private Map<String, String> environmentVariables = System.getenv();

    @Command(name = "embedded project list", description = "List a connected user's projects.")
    public void projectList(
        @Option(longName = "external-user-id", required = true) String externalUserId,
        @Option(longName = "profile") String profile,
        @Option(longName = "host") String host,
        @Option(longName = "token") String token,
        @Option(longName = "environment") String environment) {

        CliConfig config = resolve(profile, host, token, environment);

        try {
            new OutputRenderer(System.out).renderJson(
                EmbeddedConfigurationClientFactory.automationWorkflowProjectApi(config)
                    .getProjects(externalUserId, null));
        } catch (ApiException e) {
            throw EmbeddedConfigurationClientFactory.toCliException(e);
        }
    }

    @Command(name = "embedded workflow list", description = "List a connected user's project workflows.")
    public void workflowList(
        @Option(longName = "external-user-id", required = true) String externalUserId,
        @Option(longName = "profile") String profile,
        @Option(longName = "host") String host,
        @Option(longName = "token") String token,
        @Option(longName = "environment") String environment) {

        CliConfig config = resolve(profile, host, token, environment);

        try {
            new OutputRenderer(System.out).renderJson(
                workflowApi(config).getProjectWorkflows(externalUserId, null));
        } catch (ApiException e) {
            throw EmbeddedConfigurationClientFactory.toCliException(e);
        }
    }

    @Command(name = "embedded workflow get", description = "Get a connected user's project workflow.")
    public void workflowGet(
        @Option(longName = "external-user-id", required = true) String externalUserId,
        @Option(longName = "workflow-uuid", required = true) String workflowUuid,
        @Option(longName = "profile") String profile,
        @Option(longName = "host") String host,
        @Option(longName = "token") String token,
        @Option(longName = "environment") String environment) {

        CliConfig config = resolve(profile, host, token, environment);

        try {
            new OutputRenderer(System.out).renderJson(
                workflowApi(config).getProjectWorkflow(externalUserId, workflowUuid, null));
        } catch (ApiException e) {
            throw EmbeddedConfigurationClientFactory.toCliException(e);
        }
    }

    @Command(name = "embedded workflow enable", description = "Enable a connected user's project workflow.")
    public void workflowEnable(
        @Option(longName = "external-user-id", required = true) String externalUserId,
        @Option(longName = "workflow-uuid", required = true) String workflowUuid,
        @Option(longName = "profile") String profile,
        @Option(longName = "host") String host,
        @Option(longName = "token") String token,
        @Option(longName = "environment") String environment) {

        CliConfig config = resolve(profile, host, token, environment);

        try {
            workflowApi(config).enableProjectWorkflow(externalUserId, workflowUuid, null);

            new OutputRenderer(System.out).message("Enabled workflow " + workflowUuid + ".");
        } catch (ApiException e) {
            throw EmbeddedConfigurationClientFactory.toCliException(e);
        }
    }

    @Command(name = "embedded workflow disable", description = "Disable a connected user's project workflow.")
    public void workflowDisable(
        @Option(longName = "external-user-id", required = true) String externalUserId,
        @Option(longName = "workflow-uuid", required = true) String workflowUuid,
        @Option(longName = "profile") String profile,
        @Option(longName = "host") String host,
        @Option(longName = "token") String token,
        @Option(longName = "environment") String environment) {

        CliConfig config = resolve(profile, host, token, environment);

        try {
            workflowApi(config).disableProjectWorkflow(externalUserId, workflowUuid, null);

            new OutputRenderer(System.out).message("Disabled workflow " + workflowUuid + ".");
        } catch (ApiException e) {
            throw EmbeddedConfigurationClientFactory.toCliException(e);
        }
    }

    @Command(name = "embedded workflow publish", description = "Publish a connected user's project workflow.")
    public void workflowPublish(
        @Option(longName = "external-user-id", required = true) String externalUserId,
        @Option(longName = "workflow-uuid", required = true) String workflowUuid,
        @Option(longName = "description") String description,
        @Option(longName = "profile") String profile,
        @Option(longName = "host") String host,
        @Option(longName = "token") String token,
        @Option(longName = "environment") String environment) {

        CliConfig config = resolve(profile, host, token, environment);

        PublishFrontendProjectWorkflowRequestModel body = new PublishFrontendProjectWorkflowRequestModel();

        body.setDescription(description);

        try {
            workflowApi(config).publishProjectWorkflow(externalUserId, workflowUuid, body, null);

            new OutputRenderer(System.out).message("Published workflow " + workflowUuid + ".");
        } catch (ApiException e) {
            throw EmbeddedConfigurationClientFactory.toCliException(e);
        }
    }

    @Command(name = "embedded workflow delete", description = "Delete a connected user's project workflow.")
    public void workflowDelete(
        @Option(longName = "external-user-id", required = true) String externalUserId,
        @Option(longName = "workflow-uuid", required = true) String workflowUuid,
        @Option(longName = "profile") String profile,
        @Option(longName = "host") String host,
        @Option(longName = "token") String token,
        @Option(longName = "environment") String environment) {

        CliConfig config = resolve(profile, host, token, environment);

        try {
            workflowApi(config).deleteProjectWorkflow(externalUserId, workflowUuid, null);

            new OutputRenderer(System.out).message("Deleted workflow " + workflowUuid + ".");
        } catch (ApiException e) {
            throw EmbeddedConfigurationClientFactory.toCliException(e);
        }
    }

    void setConfigPath(Path configPath) {
        this.configPath = configPath;
    }

    void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    private ConnectedUserProjectWorkflowApi workflowApi(CliConfig config) {
        return EmbeddedConfigurationClientFactory.connectedUserProjectWorkflowApi(config);
    }

    private CliConfig resolve(String profile, String host, String token, String environment) {
        return EmbeddedSupport.resolve(configPath, environmentVariables, profile, host, token, environment);
    }
}
