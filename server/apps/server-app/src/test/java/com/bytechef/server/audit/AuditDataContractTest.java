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

package com.bytechef.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.audit.AuditData;
import com.bytechef.platform.audit.AuditMapper;
import com.bytechef.platform.audit.Audited;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.mcp.audit.McpServerAuditMapper;
import com.bytechef.platform.mcp.domain.McpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.Assign;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.ConstructorReference;
import org.springframework.expression.spel.ast.FunctionReference;
import org.springframework.expression.spel.ast.Indexer;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.TypeReference;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * @author Ivica Cardic
 */
class AuditDataContractTest {

    private static final String AUDIT_EVENTS_CLASS_PATTERN = "classpath*:com/bytechef/**/*AuditEvents.class";
    private static final Path AUDIT_EVENTS_DOCUMENT = Path.of(
        "docs", "content", "docs", "platform", "settings", "audit-events.mdx");
    private static final String CLASS_PATTERN = "classpath*:com/bytechef/**/*.class";
    private static final int EVENT_TYPE_COLUMN_MAX_LENGTH = 256;
    private static final String COMPONENT_PACKAGE_PATH = "/com/bytechef/component/";
    private static final String RESULT_VARIABLE = "result";
    private static final List<String> READ_METHOD_PREFIXES = List.of(
        "count", "exists", "fetch", "find", "get", "has", "is", "list", "load", "read", "search");
    private static final String AUDIT_PUBLISHER = "recorded by the module's own audit publisher or audit aspect";
    private static final String DATA_TABLE_CONTENT = "data table rows or columns: user data, not configuration";
    private static final String DEFINITION_EDIT = "workflow definition edit";
    private static final String DELEGATES = "delegates to an audited method";
    private static final String EDITOR_TEST_STATE = "editor test configuration, sample output or execution log";
    private static final String NO_AUDIT_EVENT = "no audit event is defined for this change yet";
    private static final String NOT_A_WRITE = "persists nothing: a preview, check, validation, export or test run";
    private static final String TAGS = "tag assignment";
    private static final String RECORDED_BY_CALLER = "called only from audited methods";
    private static final String TOOL_SETTING = "parameter or skill edit of a tool of an audited MCP or A2A server";
    private static final String TRIGGER_INTERNAL = "trigger bookkeeping of an audited enable";
    private static final String WORKFLOW_INPUTS = "workflow input or connection values";
    private static final Map<String, String> UNAUDITED_GUARDED_CLASSES = Map.of(
        "IntegrationInstanceAdminFacadeImpl", DELEGATES,
        "WebhookTriggerTestAdminFacadeImpl", "editor-time webhook trigger test",
        "WebhookTriggerTestApiFacadeImpl", "editor-time webhook trigger test");
    private static final Map<String, String> UNAUDITED_GUARDED_METHODS = Map.ofEntries(
        Map.entry("A2aProjectWorkflowFacadeImpl.updateA2aProjectWorkflowSkill", TOOL_SETTING),
        Map.entry("A2aProjectWorkflowServiceImpl.updateEnabled", RECORDED_BY_CALLER),
        Map.entry("A2aProjectWorkflowServiceImpl.updateSkill", TOOL_SETTING),
        Map.entry("A2aServerFacadeImpl.updateA2aServerTags", TAGS),
        Map.entry("A2aServerPromotionHandler.preview", NOT_A_WRITE),
        Map.entry("A2aServerPromotionHandler.promote", NO_AUDIT_EVENT),
        Map.entry("AgentEvalRunServiceImpl.createAgentEvalRun", NO_AUDIT_EVENT),
        Map.entry("AgentEvalRunServiceImpl.deleteAgentEvalRun", NO_AUDIT_EVENT),
        Map.entry("AgentEvalScenarioServiceImpl.createAiAgentEvalScenario", NO_AUDIT_EVENT),
        Map.entry("AgentEvalScenarioServiceImpl.deleteAiAgentEvalScenario", NO_AUDIT_EVENT),
        Map.entry("AgentEvalScenarioServiceImpl.updateAiAgentEvalScenario", NO_AUDIT_EVENT),
        Map.entry("AgentEvalTestServiceImpl.createAiAgentEvalTest", NO_AUDIT_EVENT),
        Map.entry("AgentEvalTestServiceImpl.deleteAiAgentEvalTest", NO_AUDIT_EVENT),
        Map.entry("AgentEvalTestServiceImpl.updateAiAgentEvalTest", NO_AUDIT_EVENT),
        Map.entry("AgentJudgeServiceImpl.createAiAgentJudge", NO_AUDIT_EVENT),
        Map.entry("AgentJudgeServiceImpl.deleteAiAgentJudge", NO_AUDIT_EVENT),
        Map.entry("AgentJudgeServiceImpl.updateAiAgentJudge", NO_AUDIT_EVENT),
        Map.entry("AgentScenarioJudgeServiceImpl.createAiAgentScenarioJudge", NO_AUDIT_EVENT),
        Map.entry("AgentScenarioJudgeServiceImpl.deleteAiAgentScenarioJudge", NO_AUDIT_EVENT),
        Map.entry("AgentScenarioJudgeServiceImpl.updateAiAgentScenarioJudge", NO_AUDIT_EVENT),
        Map.entry("AgentScenarioToolSimulationServiceImpl.createAiAgentScenarioToolSimulation", NO_AUDIT_EVENT),
        Map.entry("AgentScenarioToolSimulationServiceImpl.deleteAiAgentScenarioToolSimulation", NO_AUDIT_EVENT),
        Map.entry("AgentScenarioToolSimulationServiceImpl.updateAiAgentScenarioToolSimulation", NO_AUDIT_EVENT),
        Map.entry("AiAgentFacadeImpl.addAgentChannel", DEFINITION_EDIT),
        Map.entry("AiAgentFacadeImpl.addAgentElement", DEFINITION_EDIT),
        Map.entry("AiAgentFacadeImpl.copyProjectAgents", RECORDED_BY_CALLER),
        Map.entry("AiAgentFacadeImpl.createAgent", NO_AUDIT_EVENT),
        Map.entry("AiAgentFacadeImpl.deleteAgent", NO_AUDIT_EVENT),
        Map.entry("AiAgentFacadeImpl.deleteAgentChannel", DEFINITION_EDIT),
        Map.entry("AiAgentFacadeImpl.deleteAgentElement", DEFINITION_EDIT),
        Map.entry("AiAgentFacadeImpl.deleteProjectAgents", RECORDED_BY_CALLER),
        Map.entry("AiAgentFacadeImpl.exportAgent", NOT_A_WRITE),
        Map.entry("AiAgentFacadeImpl.importAgent", NO_AUDIT_EVENT),
        Map.entry("AiAgentFacadeImpl.prepareProjectPublish", RECORDED_BY_CALLER),
        Map.entry("AiAgentFacadeImpl.updateAgent", NO_AUDIT_EVENT),
        Map.entry("AiAgentFacadeImpl.updateAgentChannel", DEFINITION_EDIT),
        Map.entry("AiAgentFacadeImpl.updateAgentElement", DEFINITION_EDIT),
        Map.entry("AiAgentFacadeImpl.updateAgentFromExport", NO_AUDIT_EVENT),
        Map.entry("AiAgentFacadeImpl.updateAgentSettings", DEFINITION_EDIT),
        Map.entry("AiEvalExperimentComparisonFacadeImpl.experimentComparison", NOT_A_WRITE),
        Map.entry("AiEvalRuleFacadeImpl.createInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiEvalRuleFacadeImpl.deleteInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiEvalRuleFacadeImpl.instantiateTemplate", NO_AUDIT_EVENT),
        Map.entry("AiEvalRuleFacadeImpl.runOnHistoricalTraces", NO_AUDIT_EVENT),
        Map.entry("AiEvalRuleFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("AiEvalScoreConfigFacadeImpl.createInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiEvalScoreConfigFacadeImpl.deleteInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiEvalScoreConfigFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("AiEvalScoreFacadeImpl.createInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiEvalScoreFacadeImpl.deleteInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiGatewayBudgetFacadeImpl.createInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiGatewayBudgetFacadeImpl.delete", NO_AUDIT_EVENT),
        Map.entry("AiGatewayBudgetFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("AiGatewayPlaygroundFacadeImpl.playgroundChatCompletion", NOT_A_WRITE),
        Map.entry("AiGatewayProjectFacadeImpl.createInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiGatewayProjectFacadeImpl.delete", NO_AUDIT_EVENT),
        Map.entry("AiGatewayProjectFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("AiGatewayProjectSettingsFacadeImpl.upsert", NO_AUDIT_EVENT),
        Map.entry("AiGatewayProviderFacadeImpl.create", NO_AUDIT_EVENT),
        Map.entry("AiGatewayProviderFacadeImpl.delete", NO_AUDIT_EVENT),
        Map.entry("AiGatewayProviderFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("AiGatewayRateLimitFacadeImpl.createInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiGatewayRateLimitFacadeImpl.delete", NO_AUDIT_EVENT),
        Map.entry("AiGatewayRateLimitFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("AiGatewayRoutingPolicyFacadeImpl.create", NO_AUDIT_EVENT),
        Map.entry("AiGatewayRoutingPolicyFacadeImpl.delete", NO_AUDIT_EVENT),
        Map.entry("AiGatewayRoutingPolicyFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("AiGatewayWorkspaceSettingsFacadeImpl.upsert", NO_AUDIT_EVENT),
        Map.entry("AiHubChatSharingFacadeImpl.grantAccess", AUDIT_PUBLISHER),
        Map.entry("AiHubChatSharingFacadeImpl.revokeAccess", AUDIT_PUBLISHER),
        Map.entry("AiHubChatSharingFacadeImpl.setVisibility", AUDIT_PUBLISHER),
        Map.entry("AiHubToolApprovalRuleFacadeImpl.createRule", AUDIT_PUBLISHER),
        Map.entry("AiHubToolApprovalRuleFacadeImpl.deleteRule", AUDIT_PUBLISHER),
        Map.entry("AiHubWorkspaceSettingsFacadeImpl.updateVoiceWebhookUrl", DELEGATES),
        Map.entry("AiModelFacadeImpl.create", NO_AUDIT_EVENT),
        Map.entry("AiModelFacadeImpl.delete", NO_AUDIT_EVENT),
        Map.entry("AiModelFacadeImpl.reconcileCatalog", NO_AUDIT_EVENT),
        Map.entry("AiModelFacadeImpl.unpin", NO_AUDIT_EVENT),
        Map.entry("AiModelFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("AiObservabilityExportJobFacadeImpl.cancelExportJob", NO_AUDIT_EVENT),
        Map.entry("AiObservabilityExportJobFacadeImpl.createExportJob", NO_AUDIT_EVENT),
        Map.entry("AiPromptFacadeImpl.createInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiPromptFacadeImpl.createVersion", NO_AUDIT_EVENT),
        Map.entry("AiPromptFacadeImpl.deleteInWorkspace", NO_AUDIT_EVENT),
        Map.entry("AiPromptFacadeImpl.setActiveVersion", NO_AUDIT_EVENT),
        Map.entry("AiPromptFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("ApiClientServiceImpl.delete", NO_AUDIT_EVENT),
        Map.entry("ApiClientServiceImpl.update", NO_AUDIT_EVENT),
        Map.entry("ApiCollectionFacadeImpl.createApiCollectionEndpoint", NO_AUDIT_EVENT),
        Map.entry("ApiCollectionFacadeImpl.deleteApiCollectionEndpoint", AUDIT_PUBLISHER),
        Map.entry("ApiCollectionFacadeImpl.enableApiCollectionEndpoint", NO_AUDIT_EVENT),
        Map.entry("ApiCollectionFacadeImpl.updateApiCollection", NO_AUDIT_EVENT),
        Map.entry("ApiCollectionFacadeImpl.updateApiCollectionEndpoint", NO_AUDIT_EVENT),
        Map.entry("ApiCollectionFacadeImpl.updateApiCollectionEndpointWorkflow", WORKFLOW_INPUTS),
        Map.entry("ApiCollectionFacadeImpl.updateApiCollectionProjectVersion", AUDIT_PUBLISHER),
        Map.entry("ApiCollectionFacadeImpl.updateApiCollectionTags", TAGS),
        Map.entry("ApiCollectionPromotionHandler.preview", NOT_A_WRITE),
        Map.entry("ApiCollectionPromotionHandler.promote", NO_AUDIT_EVENT),
        Map.entry("ApiConnectorFacadeImpl.deleteApiConnector", NO_AUDIT_EVENT),
        Map.entry("ApiConnectorFacadeImpl.deleteOrphanedFiles", NO_AUDIT_EVENT),
        Map.entry("ApiConnectorFacadeImpl.generateFromDocumentation", NO_AUDIT_EVENT),
        Map.entry("ApiConnectorFacadeImpl.importOpenApiSpecification", NO_AUDIT_EVENT),
        Map.entry("ApiConnectorFacadeImpl.updateApiConnector", NO_AUDIT_EVENT),
        Map.entry("ApiConnectorServiceImpl.create", NO_AUDIT_EVENT),
        Map.entry("ApiConnectorServiceImpl.delete", NO_AUDIT_EVENT),
        Map.entry("ApiConnectorServiceImpl.enableApiConnector", NO_AUDIT_EVENT),
        Map.entry("ApiConnectorServiceImpl.update", NO_AUDIT_EVENT),
        Map.entry("AppApiCollectionFacadeImpl.bindApiCollection", AUDIT_PUBLISHER),
        Map.entry("AppApiCollectionFacadeImpl.unbindApiCollection", AUDIT_PUBLISHER),
        Map.entry("AppEventServiceImpl.create", RECORDED_BY_CALLER),
        Map.entry("AppEventServiceImpl.delete", RECORDED_BY_CALLER),
        Map.entry("AppEventServiceImpl.update", RECORDED_BY_CALLER),
        Map.entry("AppFacadeImpl.bindConnection", AUDIT_PUBLISHER),
        Map.entry("AppFacadeImpl.createApp", AUDIT_PUBLISHER),
        Map.entry("AppFacadeImpl.deleteApp", AUDIT_PUBLISHER),
        Map.entry("AppFacadeImpl.rotateSecretKey", AUDIT_PUBLISHER),
        Map.entry("AppFacadeImpl.unbindConnection", AUDIT_PUBLISHER),
        Map.entry("AppFacadeImpl.updateApp", AUDIT_PUBLISHER),
        Map.entry("AppFacadeImpl.updateAppStatus", AUDIT_PUBLISHER),
        Map.entry("AppWorkflowFacadeImpl.bindWorkflow", AUDIT_PUBLISHER),
        Map.entry("AppWorkflowFacadeImpl.unbindWorkflow", AUDIT_PUBLISHER),
        Map.entry("AutomationWorkflowProjectCodeWorkflowFacadeImpl.save", DEFINITION_EDIT),
        Map.entry("AutomationWorkflowProjectFacadeImpl.updateProjectWorkflow", DEFINITION_EDIT),
        Map.entry("AutomationWorkflowProjectConfigurationAdminFacadeImpl.updateConfigurationWorkflow", WORKFLOW_INPUTS),
        Map.entry("ComponentPolicyServiceImpl.updateComponentOperationPolicy", NO_AUDIT_EVENT),
        Map.entry("ComponentPolicyServiceImpl.updateComponentPolicy", NO_AUDIT_EVENT),
        Map.entry("ComponentRuleServiceImpl.deleteComponentRule", NO_AUDIT_EVENT),
        Map.entry("ComponentRuleServiceImpl.saveComponentRule", NO_AUDIT_EVENT),
        Map.entry("ComponentRuleSettingsServiceImpl.saveSettings", NO_AUDIT_EVENT),
        Map.entry("ConnectedUserAiGatewayFacadeImpl.assignRoutingPolicy", NO_AUDIT_EVENT),
        Map.entry("ConnectedUserAiGatewayFacadeImpl.createProvider", NO_AUDIT_EVENT),
        Map.entry("ConnectedUserAiGatewayFacadeImpl.deleteProvider", NO_AUDIT_EVENT),
        Map.entry("ConnectedUserAiGatewayFacadeImpl.unassignRoutingPolicy", NO_AUDIT_EVENT),
        Map.entry("ConnectedUserAiGatewayFacadeImpl.updateBudgetCap", NO_AUDIT_EVENT),
        Map.entry("ConnectionReassignmentFacadeImpl.markConnectionsPendingReassignmentAsAdmin", NO_AUDIT_EVENT),
        Map.entry("ConnectionReassignmentFacadeImpl.reassignAllConnections", AUDIT_PUBLISHER),
        Map.entry("ConnectionReassignmentFacadeImpl.reassignConnection", AUDIT_PUBLISHER),
        Map.entry("ContextStoreFacadeImpl.createContextStore", NO_AUDIT_EVENT),
        Map.entry("ContextStoreFacadeImpl.deleteContextStore", NO_AUDIT_EVENT),
        Map.entry("ContextStoreFacadeImpl.updateContextStore", NO_AUDIT_EVENT),
        Map.entry("ContextStoreFacadeImpl.updateContextStoreTags", TAGS),
        Map.entry("ContextStoreSourceFacadeImpl.createContextStoreSource", AUDIT_PUBLISHER),
        Map.entry("ContextStoreSourceFacadeImpl.deleteContextStoreSource", AUDIT_PUBLISHER),
        Map.entry("ContextStoreSourceFacadeImpl.refreshContextStoreSource", NO_AUDIT_EVENT),
        Map.entry("ContextStoreSourceFacadeImpl.setContextStoreSourceEnabled", AUDIT_PUBLISHER),
        Map.entry("ContextStoreSourceFacadeImpl.updateContextStoreSource", AUDIT_PUBLISHER),
        Map.entry("CustomComponentFacadeImpl.createEmptyCustomComponent", AUDIT_PUBLISHER),
        Map.entry("CustomComponentFacadeImpl.delete", AUDIT_PUBLISHER),
        Map.entry("CustomComponentFacadeImpl.publishCustomComponent", AUDIT_PUBLISHER),
        Map.entry("CustomComponentFacadeImpl.updateCustomComponentSource", AUDIT_PUBLISHER),
        Map.entry("CustomComponentServiceImpl.enableCustomComponent", AUDIT_PUBLISHER),
        Map.entry("CustomComponentServiceImpl.publishCustomComponent", AUDIT_PUBLISHER),
        Map.entry("CustomRoleServiceImpl.createCustomRole", AUDIT_PUBLISHER),
        Map.entry("CustomRoleServiceImpl.deleteCustomRole", AUDIT_PUBLISHER),
        Map.entry("CustomRoleServiceImpl.updateCustomRole", AUDIT_PUBLISHER),
        Map.entry("DataSyncFacadeImpl.copyProjectDataSyncs", RECORDED_BY_CALLER),
        Map.entry("DataSyncFacadeImpl.createDataSync", NO_AUDIT_EVENT),
        Map.entry("DataSyncFacadeImpl.deleteDataSync", NO_AUDIT_EVENT),
        Map.entry("DataSyncFacadeImpl.deleteProjectDataSyncs", RECORDED_BY_CALLER),
        Map.entry("DataSyncFacadeImpl.exportDataSync", NOT_A_WRITE),
        Map.entry("DataSyncFacadeImpl.importDataSync", NO_AUDIT_EVENT),
        Map.entry("DataSyncFacadeImpl.prepareProjectPublish", RECORDED_BY_CALLER),
        Map.entry("DataSyncFacadeImpl.runDataSyncDeployment", NO_AUDIT_EVENT),
        Map.entry("DataSyncFacadeImpl.setDataSyncElement", DEFINITION_EDIT),
        Map.entry("DataSyncFacadeImpl.updateDataSync", NO_AUDIT_EVENT),
        Map.entry("DataSyncFacadeImpl.updateDataSyncElement", DEFINITION_EDIT),
        Map.entry("DataSyncFacadeImpl.updateDataSyncFromExport", NO_AUDIT_EVENT),
        Map.entry("DataSyncFacadeImpl.updateDataSyncTrigger", DEFINITION_EDIT),
        Map.entry("EditorLogFileStorageImpl.logsExist", NOT_A_WRITE),
        Map.entry("EmbeddedMcpServerFacadeImpl.createEmbeddedMcpComponent", DELEGATES),
        Map.entry("EmbeddedMcpServerFacadeImpl.createEmbeddedMcpServer", DELEGATES),
        Map.entry("EmbeddedMcpServerFacadeImpl.deleteEmbeddedMcpComponent", DELEGATES),
        Map.entry("EmbeddedMcpServerFacadeImpl.deleteEmbeddedMcpServer", DELEGATES),
        Map.entry("EmbeddedMcpServerFacadeImpl.deleteEmbeddedMcpTool", DELEGATES),
        Map.entry("EmbeddedMcpServerFacadeImpl.updateEmbeddedMcpComponent", DELEGATES),
        Map.entry("EmbeddedMcpServerFacadeImpl.updateEmbeddedMcpServer", DELEGATES),
        Map.entry("EmbeddedMcpServerFacadeImpl.updateEmbeddedMcpServerSecretKey", DELEGATES),
        Map.entry("EmbeddedMcpServerFacadeImpl.updateEmbeddedMcpServerTags", TAGS),
        Map.entry("EmbeddedMcpServerFacadeImpl.updateEmbeddedMcpTool", DELEGATES),
        Map.entry("GitConfigurationFacadeImpl.save", NO_AUDIT_EVENT),
        Map.entry("IntegrationCodeWorkflowFacadeImpl.createEmptyCodeWorkflow", NO_AUDIT_EVENT),
        Map.entry("IntegrationCodeWorkflowFacadeImpl.save", DEFINITION_EDIT),
        Map.entry("IntegrationCodeWorkflowFacadeImpl.updateCodeWorkflowSource", DEFINITION_EDIT),
        Map.entry("IntegrationInstanceConfigurationFacadeImpl.updateIntegrationInstanceConfigurationTags", TAGS),
        Map.entry(
            "IntegrationInstanceConfigurationFacadeImpl.updateIntegrationInstanceConfigurationWorkflow",
            WORKFLOW_INPUTS),
        Map.entry("IntegrationInstanceFacadeImpl.enableIntegrationInstanceWorkflowTriggers", TRIGGER_INTERNAL),
        Map.entry(
            "IntegrationInstanceFacadeImpl.updateIntegrationInstanceConfigurationWorkflowTriggers", TRIGGER_INTERNAL),
        Map.entry("IntegrationInstanceFacadeImpl.updateIntegrationInstanceWorkflow", WORKFLOW_INPUTS),
        Map.entry("IntegrationTagFacadeImpl.updateIntegrationTags", TAGS),
        Map.entry("IntegrationWorkflowFacadeImpl.updateWorkflow", DEFINITION_EDIT),
        Map.entry("LogFileStorageImpl.deleteLogEntries", EDITOR_TEST_STATE),
        Map.entry("LogFileStorageImpl.logsExist", NOT_A_WRITE),
        Map.entry(
            "McpIntegrationInstanceConfigurationWorkflowFacadeImpl.updateMcpIntegrationInstanceConfigurationWorkflow",
            TOOL_SETTING),
        Map.entry(
            "McpIntegrationInstanceConfigurationWorkflowFacadeImpl.updateMcpIntegrationInstanceConfigurationWorkflowParameters",
            TOOL_SETTING),
        Map.entry("McpIntegrationInstanceWorkflowFacadeImpl.updateMcpIntegrationInstanceWorkflow", TOOL_SETTING),
        Map.entry("McpProjectWorkflowFacadeImpl.updateMcpProjectWorkflowParameters", TOOL_SETTING),
        Map.entry("McpProjectWorkflowServiceImpl.create", RECORDED_BY_CALLER),
        Map.entry("McpProjectWorkflowServiceImpl.delete", RECORDED_BY_CALLER),
        Map.entry("McpProjectWorkflowServiceImpl.update", TOOL_SETTING),
        Map.entry("McpProjectWorkflowServiceImpl.updateEnabled", RECORDED_BY_CALLER),
        Map.entry("McpProjectWorkflowServiceImpl.updateParameters", TOOL_SETTING),
        Map.entry("McpServerFacadeImpl.create", DELEGATES),
        Map.entry("McpServerFacadeImpl.createMcpComponent", DELEGATES),
        Map.entry("McpServerFacadeImpl.createMcpTool", DELEGATES),
        Map.entry("McpServerFacadeImpl.deleteMcpComponent", DELEGATES),
        Map.entry("McpServerFacadeImpl.deleteMcpServer", DELEGATES),
        Map.entry("McpServerFacadeImpl.deleteMcpTool", DELEGATES),
        Map.entry("McpServerFacadeImpl.rotateMcpServerSecretKey", DELEGATES),
        Map.entry("McpServerFacadeImpl.update", DELEGATES),
        Map.entry("McpServerFacadeImpl.updateMcpServer", DELEGATES),
        Map.entry("McpServerFacadeImpl.updateMcpServerTags", TAGS),
        Map.entry("McpServerFacadeImpl.updateMcpTool", DELEGATES),
        Map.entry("McpServerPromotionHandler.preview", NOT_A_WRITE),
        Map.entry("McpServerPromotionHandler.promote", NO_AUDIT_EVENT),
        Map.entry("NotificationServiceImpl.delete", NO_AUDIT_EVENT),
        Map.entry("OrganizationConnectionFacadeImpl.create", AUDIT_PUBLISHER),
        Map.entry("OrganizationConnectionFacadeImpl.delete", AUDIT_PUBLISHER),
        Map.entry("OrganizationConnectionFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("ProjectCodeWorkflowFacadeImpl.createEmptyCodeWorkflow", NO_AUDIT_EVENT),
        Map.entry("ProjectCodeWorkflowFacadeImpl.deployCodeWorkflowSource", DEFINITION_EDIT),
        Map.entry("ProjectCodeWorkflowFacadeImpl.save", DEFINITION_EDIT),
        Map.entry("ProjectCodeWorkflowFacadeImpl.updateCodeWorkflowSource", DEFINITION_EDIT),
        Map.entry("ProjectDeploymentFacadeImpl.checkEnableProjectDeployment", NOT_A_WRITE),
        Map.entry("ProjectDeploymentFacadeImpl.deployProject", AUDIT_PUBLISHER),
        Map.entry("ProjectDeploymentFacadeImpl.updateProjectDeploymentTags", TAGS),
        Map.entry("ProjectDeploymentFacadeImpl.updateProjectDeploymentWorkflow", WORKFLOW_INPUTS),
        Map.entry("ProjectDeploymentPromotionHandler.preview", NOT_A_WRITE),
        Map.entry("ProjectDeploymentPromotionHandler.promote", NO_AUDIT_EVENT),
        Map.entry("ProjectFacadeImpl.updateProjectErrorWorkflow", NO_AUDIT_EVENT),
        Map.entry("ProjectGitFacadeImpl.pullProjectFromGit", NO_AUDIT_EVENT),
        Map.entry("ProjectGitFacadeImpl.pushProjectToGit", NO_AUDIT_EVENT),
        Map.entry("ProjectSharingFacadeImpl.grantProjectAccess", AUDIT_PUBLISHER),
        Map.entry("ProjectSharingFacadeImpl.revokeProjectAccess", AUDIT_PUBLISHER),
        Map.entry("ProjectSharingFacadeImpl.setProjectVisibility", AUDIT_PUBLISHER),
        Map.entry("ProjectTagFacadeImpl.updateProjectTags", TAGS),
        Map.entry("ProjectWorkflowFacadeImpl.deleteSharedWorkflow", NO_AUDIT_EVENT),
        Map.entry("ProjectWorkflowFacadeImpl.exportSharedWorkflow", NO_AUDIT_EVENT),
        Map.entry("ProjectWorkflowFacadeImpl.importWorkflowTemplate", AUDIT_PUBLISHER),
        Map.entry("ProjectWorkflowFacadeImpl.saveWorkflowTestConnection", EDITOR_TEST_STATE),
        Map.entry("ProjectWorkflowFacadeImpl.updateWorkflow", DEFINITION_EDIT),
        Map.entry("ProjectWorkflowFacadeImpl.updateWorkflowErrorWorkflow", NO_AUDIT_EVENT),
        Map.entry("TaskDispatcherDefinitionFacadeImpl.executeOutput", NOT_A_WRITE),
        Map.entry("WorkflowNodeOutputFacadeImpl.checkWorkflowCache", NOT_A_WRITE),
        Map.entry("WorkflowNodeParameterFacadeImpl.deleteClusterElementParameter", DEFINITION_EDIT),
        Map.entry("WorkflowNodeParameterFacadeImpl.deleteWorkflowNodeParameter", DEFINITION_EDIT),
        Map.entry("WorkflowNodeParameterFacadeImpl.updateClusterElementParameter", DEFINITION_EDIT),
        Map.entry("WorkflowNodeParameterFacadeImpl.updateWorkflowNodeParameter", DEFINITION_EDIT),
        Map.entry("WorkflowNodeScriptFacadeImpl.testClusterElementScript", NOT_A_WRITE),
        Map.entry("WorkflowNodeScriptFacadeImpl.testWorkflowNodeScript", NOT_A_WRITE),
        Map.entry("WorkflowNodeTestOutputFacadeImpl.saveClusterElementTestOutput", EDITOR_TEST_STATE),
        Map.entry("WorkflowNodeTestOutputFacadeImpl.saveWorkflowNodeSampleFile", EDITOR_TEST_STATE),
        Map.entry("WorkflowNodeTestOutputFacadeImpl.saveWorkflowNodeSampleOutput", EDITOR_TEST_STATE),
        Map.entry("WorkflowNodeTestOutputFacadeImpl.saveWorkflowNodeTestOutput", EDITOR_TEST_STATE),
        Map.entry("WorkflowNodeTestOutputServiceImpl.checkWorkflowNodeTestOutputExists", NOT_A_WRITE),
        Map.entry("WorkflowNodeTestOutputServiceImpl.deleteWorkflowNodeTestOutput", EDITOR_TEST_STATE),
        Map.entry("WorkflowTestConfigurationFacadeImpl.deleteWorkflowTestConfigurationConnection", EDITOR_TEST_STATE),
        Map.entry(
            "WorkflowTestConfigurationFacadeImpl.saveClusterElementTestConfigurationConnection", EDITOR_TEST_STATE),
        Map.entry("WorkflowTestConfigurationFacadeImpl.saveWorkflowTestConfiguration", EDITOR_TEST_STATE),
        Map.entry("WorkflowTestConfigurationFacadeImpl.saveWorkflowTestConfigurationConnection", EDITOR_TEST_STATE),
        Map.entry("WorkflowTestConfigurationFacadeImpl.saveWorkflowTestConfigurationInputs", EDITOR_TEST_STATE),
        Map.entry("WorkflowValidatorFacadeImpl.validateWorkflow", NOT_A_WRITE),
        Map.entry("WorkflowValidatorFacadeImpl.validateWorkflowById", NOT_A_WRITE),
        Map.entry("WorkspaceAiGatewayProviderFacadeImpl.createWorkspaceProvider", NO_AUDIT_EVENT),
        Map.entry("WorkspaceAiGatewayProviderFacadeImpl.deleteWorkspaceProvider", NO_AUDIT_EVENT),
        Map.entry("WorkspaceAiGatewayProviderFacadeImpl.testWorkspaceProviderConnection", NOT_A_WRITE),
        Map.entry("WorkspaceAiGatewayProviderFacadeImpl.updateWorkspaceProvider", NO_AUDIT_EVENT),
        Map.entry("WorkspaceAiGatewayRoutingPolicyFacadeImpl.createWorkspaceRoutingPolicy", NO_AUDIT_EVENT),
        Map.entry("WorkspaceAiGatewayRoutingPolicyFacadeImpl.deleteWorkspaceRoutingPolicy", NO_AUDIT_EVENT),
        Map.entry("WorkspaceAiGatewayRoutingPolicyFacadeImpl.updateWorkspaceRoutingPolicy", NO_AUDIT_EVENT),
        Map.entry("WorkspaceAiModelFacadeImpl.createWorkspaceModel", NO_AUDIT_EVENT),
        Map.entry("WorkspaceAiModelFacadeImpl.deleteWorkspaceModel", NO_AUDIT_EVENT),
        Map.entry("WorkspaceAiModelFacadeImpl.unpinWorkspaceModel", NO_AUDIT_EVENT),
        Map.entry("WorkspaceAiModelFacadeImpl.updateWorkspaceModel", NO_AUDIT_EVENT),
        Map.entry("WorkspaceApiKeyFacadeImpl.create", DELEGATES),
        Map.entry("WorkspaceApiKeyFacadeImpl.delete", DELEGATES),
        Map.entry("WorkspaceConnectionFacadeImpl.delete", NO_AUDIT_EVENT),
        Map.entry("WorkspaceConnectionFacadeImpl.grantConnectionAccess", AUDIT_PUBLISHER),
        Map.entry("WorkspaceConnectionFacadeImpl.registerExisting", NO_AUDIT_EVENT),
        Map.entry("WorkspaceConnectionFacadeImpl.revokeConnectionAccess", AUDIT_PUBLISHER),
        Map.entry("WorkspaceConnectionFacadeImpl.setConnectionVisibility", AUDIT_PUBLISHER),
        Map.entry("WorkspaceConnectionFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("WorkspaceConnectionFacadeImpl.updateConnectionCredentials", AUDIT_PUBLISHER),
        Map.entry("WorkspaceConnectionFacadeImpl.updateTags", TAGS),
        Map.entry("WorkspaceDataTableFacadeImpl.addColumn", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.clearRows", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.deleteRow", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.deleteRowByExternalId", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.deleteRows", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.dropTable", NO_AUDIT_EVENT),
        Map.entry("WorkspaceDataTableFacadeImpl.exportCsv", NOT_A_WRITE),
        Map.entry("WorkspaceDataTableFacadeImpl.importCsv", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.insertRow", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.insertRows", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.removeColumn", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.renameColumn", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.renameTable", NO_AUDIT_EVENT),
        Map.entry("WorkspaceDataTableFacadeImpl.updateDescription", NO_AUDIT_EVENT),
        Map.entry("WorkspaceDataTableFacadeImpl.updateRow", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceDataTableFacadeImpl.updateTags", TAGS),
        Map.entry("WorkspaceDataTableFacadeImpl.upsertRow", DATA_TABLE_CONTENT),
        Map.entry("WorkspaceKnowledgeBaseFacadeImpl.updateKnowledgeBase", NO_AUDIT_EVENT),
        Map.entry("WorkspaceKnowledgeBaseSourceFacadeImpl.create", NO_AUDIT_EVENT),
        Map.entry("WorkspaceKnowledgeBaseSourceFacadeImpl.delete", NO_AUDIT_EVENT),
        Map.entry("WorkspaceKnowledgeBaseSourceFacadeImpl.refreshNow", NO_AUDIT_EVENT),
        Map.entry("WorkspaceKnowledgeBaseSourceFacadeImpl.setEnabled", NO_AUDIT_EVENT),
        Map.entry("WorkspaceKnowledgeBaseSourceFacadeImpl.update", NO_AUDIT_EVENT),
        Map.entry("WorkspaceMcpServerFacadeImpl.createWorkspaceMcpServer", DELEGATES),
        Map.entry("WorkspaceMcpServerFacadeImpl.deleteWorkspaceMcpServer", DELEGATES),
        Map.entry("WorkspaceMcpServerFacadeImpl.updateWorkspaceMcpServer", DELEGATES),
        Map.entry("WorkspaceServiceImpl.create", RECORDED_BY_CALLER),
        Map.entry("WorkspaceServiceImpl.delete", RECORDED_BY_CALLER),
        Map.entry("WorkspaceServiceImpl.update", RECORDED_BY_CALLER),
        Map.entry("WorkspaceUserFacadeImpl.addWorkspaceUser", DELEGATES),
        Map.entry("WorkspaceUserFacadeImpl.assignCustomRole", AUDIT_PUBLISHER),
        Map.entry("WorkspaceUserFacadeImpl.inviteWorkspaceUser", AUDIT_PUBLISHER),
        Map.entry("WorkspaceUserFacadeImpl.removeEnvironmentRole", AUDIT_PUBLISHER),
        Map.entry("WorkspaceUserFacadeImpl.removeWorkspaceUser", DELEGATES),
        Map.entry("WorkspaceUserFacadeImpl.setEnvironmentRole", AUDIT_PUBLISHER),
        Map.entry("WorkspaceUserFacadeImpl.updateWorkspaceUserRole", DELEGATES));
    private static final List<String> NON_SECRET_NAME_SUFFIXES = List.of(
        "count", "id", "ids", "name", "names", "type");
    private static final List<String> SECRET_NAME_FRAGMENTS = List.of(
        "apikey", "credential", "parameters", "password", "privatekey", "secret", "token");

    private static final SpelExpressionParser SPEL_EXPRESSION_PARSER = new SpelExpressionParser();

    private static List<Method> auditedMethods;
    private static List<Method> guardedMethods;

    @BeforeAll
    static void beforeAll() throws IOException, ClassNotFoundException {
        auditedMethods = findAuditedMethods();
        guardedMethods = findGuardedMethods();
    }

    @Test
    void testAuditedMethodsAreFound() {
        assertThat(auditedMethods).hasSizeGreaterThan(150);
    }

    @Test
    void testEveryGuardedWriteIsAuditedOrDeliberatelySkipped() {
        assertThat(guardedMethods).hasSizeGreaterThan(250);

        Set<String> unauditedMethods = new TreeSet<>();
        Set<String> usedSkips = new HashSet<>();

        for (Method method : guardedMethods) {
            String methodName = method.getName();

            if (isReadMethod(methodName) || method.isAnnotationPresent(Audited.class)) {
                continue;
            }

            Class<?> declaringClass = method.getDeclaringClass();

            String className = declaringClass.getSimpleName();

            if (UNAUDITED_GUARDED_CLASSES.containsKey(className)) {
                usedSkips.add(className);

                continue;
            }

            String qualifiedMethodName = className + "." + methodName;

            if (UNAUDITED_GUARDED_METHODS.containsKey(qualifiedMethodName)) {
                usedSkips.add(qualifiedMethodName);

                continue;
            }

            unauditedMethods.add(qualifiedMethodName);
        }

        Set<String> staleSkips = new TreeSet<>(UNAUDITED_GUARDED_CLASSES.keySet());

        staleSkips.addAll(UNAUDITED_GUARDED_METHODS.keySet());
        staleSkips.removeAll(usedSkips);

        assertThat(unauditedMethods).isEmpty();
        assertThat(staleSkips).isEmpty();
    }

    @Test
    void testNoAuditedMethodNamesAParameterResult() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            for (Parameter parameter : method.getParameters()) {
                if (RESULT_VARIABLE.equals(parameter.getName())) {
                    Class<?> declaringClass = method.getDeclaringClass();

                    violations.add(declaringClass.getName() + "." + method.getName());
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testEveryAuditedEventIsDocumented() throws IOException {
        String document = Files.readString(findAuditEventsDocument());

        Set<String> undocumentedEvents = new TreeSet<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            String event = audited.event();

            if (!document.contains("`" + event + "`")) {
                undocumentedEvents.add(event);
            }
        }

        assertThat(undocumentedEvents).isEmpty();
    }

    @Test
    void testEveryDeclaredAuditEventIsAudited() throws IOException, ClassNotFoundException, IllegalAccessException {
        Set<String> auditedEvents = new HashSet<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            auditedEvents.add(audited.event());
        }

        List<Class<?>> auditEventsClasses = findAuditEventsClasses();

        assertThat(auditEventsClasses).isNotEmpty();

        List<String> unusedEvents = new ArrayList<>();

        for (Class<?> auditEventsClass : auditEventsClasses) {
            for (Field field : auditEventsClass.getDeclaredFields()) {
                int modifiers = field.getModifiers();

                if (!Modifier.isStatic(modifiers) || !String.class.equals(field.getType())) {
                    continue;
                }

                String event = (String) field.get(null);

                if (!auditedEvents.contains(event)) {
                    unusedEvents.add(auditEventsClass.getName() + "." + field.getName());
                }
            }
        }

        assertThat(unusedEvents).isEmpty();
    }

    @Test
    void testEveryAuditDataExpressionReadsADeclaredVariableAndProperty() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            for (AuditData auditData : audited.data()) {
                checkExpression(method, auditData, violations);
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testNoAuditDataExpressionCallsMethodsOrReachesOutsideItsVariables() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            for (AuditData auditData : audited.data()) {
                collectForbiddenNodes(method, auditData, parse(auditData.value()), violations);
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testEveryAuditDataKeyIsUsable() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            Set<String> keys = new HashSet<>();

            for (AuditData auditData : audited.data()) {
                String key = auditData.key();

                if (key.isBlank()) {
                    violations.add(describe(method, auditData) + " has a blank key");
                } else if (AuditData.RESERVED_KEYS.contains(key)) {
                    violations.add(describe(method, auditData) + " uses the reserved key '" + key + "'");
                } else if (!keys.add(key)) {
                    violations.add(describe(method, auditData) + " declares the key '" + key + "' more than once");
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testEveryAuditedMethodDeclaresAtMostOnePlatform() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            PlatformType[] platforms = audited.platform();

            if (platforms.length > 1) {
                violations.add(
                    method.getDeclaringClass()
                        .getName() + "#" + method.getName() + " declares " + platforms.length + " platforms");
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testNoAuditedMethodIsDeclaredOnAnInterface() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Class<?> declaringClass = method.getDeclaringClass();

            if (declaringClass.isInterface()) {
                violations.add(declaringClass.getName() + "#" + method.getName());
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testEveryAuditedMethodCanBeAdvised() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Class<?> declaringClass = method.getDeclaringClass();

            String methodName = declaringClass.getName() + "#" + method.getName();

            int modifiers = method.getModifiers();

            if (Modifier.isPrivate(modifiers)) {
                violations.add(methodName + " is private");
            }

            if (Modifier.isFinal(modifiers)) {
                violations.add(methodName + " is final");
            }

            if (Modifier.isStatic(modifiers)) {
                violations.add(methodName + " is static");
            }

            if (Modifier.isFinal(declaringClass.getModifiers())) {
                violations.add(methodName + " is declared in a final class");
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testEveryAuditedEventFitsTheEventTypeColumn() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            String event = audited.event();

            if (event.isBlank() || event.length() > EVENT_TYPE_COLUMN_MAX_LENGTH) {
                violations.add(
                    method.getDeclaringClass()
                        .getName() + "#" + method.getName() + " declares event '" + event + "'");
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testEveryAuditedMapperIsAConcreteComponent() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            Class<? extends AuditMapper> mapperClass = audited.mapper();

            if (mapperClass == AuditMapper.None.class) {
                continue;
            }

            if (mapperClass.isInterface() || Modifier.isAbstract(mapperClass.getModifiers()) ||
                !AnnotatedElementUtils.hasAnnotation(mapperClass, Component.class)) {

                violations.add(
                    method.getDeclaringClass()
                        .getName() + "#" + method.getName() + " names mapper " + mapperClass.getName());
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testEveryMcpServerAuditedMethodExposesTheMcpServer() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            if (audited.mapper() != McpServerAuditMapper.class || McpServer.class.equals(method.getReturnType())) {
                continue;
            }

            boolean exposesMcpServer = false;

            for (Parameter parameter : method.getParameters()) {
                String name = parameter.getName();
                Class<?> type = parameter.getType();

                if ((name.equals("mcpServerId") || name.equals("id")) &&
                    (type.equals(Long.class) || type.equals(long.class))) {

                    exposesMcpServer = true;
                } else if (name.equals("mcpServer") && McpServer.class.equals(type)) {
                    exposesMcpServer = true;
                }
            }

            if (!exposesMcpServer) {
                violations.add(
                    method.getDeclaringClass()
                        .getName() + "#" + method.getName() +
                        " neither returns an McpServer nor takes an mcpServerId, id or mcpServer argument");
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testNoAuditDataExpressionReadsASecret() {
        List<String> violations = new ArrayList<>();

        for (Method method : auditedMethods) {
            Audited audited = method.getAnnotation(Audited.class);

            for (AuditData auditData : audited.data()) {
                SpelNode rootNode = parse(auditData.value());

                List<String> names = new ArrayList<>();

                collectNames(rootNode, names);

                for (String name : names) {
                    String lowerCaseName = name.toLowerCase(Locale.ENGLISH);

                    if (isSecretName(lowerCaseName)) {
                        violations.add(describe(method, auditData) + " reads the secret '" + name + "'");
                    }
                }

                Class<?> returnType = method.getReturnType();

                if (rootNode instanceof VariableReference && isVariable(rootNode, RESULT_VARIABLE) &&
                    !isScalar(returnType) && !isStringId(returnType, auditData.key())) {

                    violations.add(
                        describe(method, auditData) + " records a whole " + returnType.getSimpleName() +
                            " result, which may hold a secret");
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    private static boolean isReadMethod(String methodName) {
        for (String readMethodPrefix : READ_METHOD_PREFIXES) {
            if (methodName.equals(readMethodPrefix)) {
                return true;
            }

            if (methodName.startsWith(readMethodPrefix) &&
                Character.isUpperCase(methodName.charAt(readMethodPrefix.length()))) {

                return true;
            }
        }

        return false;
    }

    private static boolean isScalar(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || Number.class.isAssignableFrom(type) || type == Boolean.class;
    }

    private static boolean isStringId(Class<?> type, String key) {
        return type == String.class && (key.endsWith("Id") || key.endsWith("Uuid"));
    }

    private static boolean isSecretName(String lowerCaseName) {
        for (String nonSecretNameSuffix : NON_SECRET_NAME_SUFFIXES) {
            if (lowerCaseName.endsWith(nonSecretNameSuffix)) {
                return false;
            }
        }

        for (String secretNameFragment : SECRET_NAME_FRAGMENTS) {
            if (lowerCaseName.contains(secretNameFragment)) {
                return true;
            }
        }

        return false;
    }

    private static void checkExpression(Method method, AuditData auditData, List<String> violations) {
        Map<String, Class<?>> variableTypes = getVariableTypes(method);

        SpelNode rootNode = parse(auditData.value());

        List<String> variableNames = new ArrayList<>();

        collectVariableNames(rootNode, variableNames);

        for (String variableName : variableNames) {
            if (!variableTypes.containsKey(variableName)) {
                violations.add(describe(method, auditData) + " names the undeclared variable #" + variableName);
            }
        }

        if (!(rootNode instanceof CompoundExpression) || rootNode.getChildCount() < 2) {
            return;
        }

        SpelNode variableNode = rootNode.getChild(0);
        SpelNode propertyNode = rootNode.getChild(1);

        if (!(variableNode instanceof VariableReference) ||
            !(propertyNode instanceof PropertyOrFieldReference propertyOrFieldReference)) {

            return;
        }

        String variableReference = variableNode.toStringAST();

        Class<?> variableType = variableTypes.get(variableReference.substring(1));

        if (variableType == null || variableType == Object.class || Map.class.isAssignableFrom(variableType)) {
            return;
        }

        String propertyName = propertyOrFieldReference.getName();

        if (!hasReadableProperty(variableType, propertyName)) {
            violations.add(
                describe(method, auditData) + " reads '" + propertyName + "', which " + variableType.getName() +
                    " does not expose");
        }
    }

    private static void collectForbiddenNodes(
        Method method, AuditData auditData, SpelNode spelNode, List<String> violations) {

        if (spelNode instanceof Assign || spelNode instanceof BeanReference ||
            spelNode instanceof ConstructorReference || spelNode instanceof FunctionReference ||
            spelNode instanceof Indexer || spelNode instanceof MethodReference || spelNode instanceof TypeReference) {

            Class<? extends SpelNode> nodeClass = spelNode.getClass();

            violations.add(
                describe(method, auditData) + " uses " + nodeClass.getSimpleName() + " '" + spelNode.toStringAST() +
                    "'");
        }

        for (int index = 0; index < spelNode.getChildCount(); index++) {
            collectForbiddenNodes(method, auditData, spelNode.getChild(index), violations);
        }
    }

    private static void collectNames(SpelNode spelNode, List<String> names) {
        if (spelNode instanceof VariableReference) {
            String variableReference = spelNode.toStringAST();

            names.add(variableReference.substring(1));
        }

        if (spelNode instanceof PropertyOrFieldReference propertyOrFieldReference) {
            names.add(propertyOrFieldReference.getName());
        }

        for (int index = 0; index < spelNode.getChildCount(); index++) {
            collectNames(spelNode.getChild(index), names);
        }
    }

    private static void collectVariableNames(SpelNode spelNode, List<String> variableNames) {
        if (spelNode instanceof VariableReference) {
            String variableReference = spelNode.toStringAST();

            variableNames.add(variableReference.substring(1));
        }

        for (int index = 0; index < spelNode.getChildCount(); index++) {
            collectVariableNames(spelNode.getChild(index), variableNames);
        }
    }

    private static String describe(Method method, AuditData auditData) {
        Class<?> declaringClass = method.getDeclaringClass();

        return declaringClass.getName() + "." + method.getName() + " data '" + auditData.key() + "' = '" +
            auditData.value() + "'";
    }

    private static List<Class<?>> findAuditEventsClasses() throws IOException, ClassNotFoundException {
        PathMatchingResourcePatternResolver pathMatchingResourcePatternResolver =
            new PathMatchingResourcePatternResolver();
        SimpleMetadataReaderFactory simpleMetadataReaderFactory = new SimpleMetadataReaderFactory();

        ClassLoader classLoader = AuditDataContractTest.class.getClassLoader();

        List<Class<?>> auditEventsClasses = new ArrayList<>();

        for (Resource resource : pathMatchingResourcePatternResolver.getResources(AUDIT_EVENTS_CLASS_PATTERN)) {
            MetadataReader metadataReader = simpleMetadataReaderFactory.getMetadataReader(resource);

            AnnotationMetadata annotationMetadata = metadataReader.getAnnotationMetadata();

            Class<?> auditEventsClass = Class.forName(annotationMetadata.getClassName(), false, classLoader);

            auditEventsClasses.add(auditEventsClass);
        }

        return auditEventsClasses;
    }

    private static Path findAuditEventsDocument() {
        Path directory = Path.of("")
            .toAbsolutePath();

        while (directory != null) {
            Path document = directory.resolve(AUDIT_EVENTS_DOCUMENT);

            if (Files.isRegularFile(document)) {
                return document;
            }

            directory = directory.getParent();
        }

        throw new IllegalStateException("Cannot find " + AUDIT_EVENTS_DOCUMENT + " above the working directory");
    }

    private static List<Method> findAuditedMethods() throws IOException, ClassNotFoundException {
        PathMatchingResourcePatternResolver pathMatchingResourcePatternResolver =
            new PathMatchingResourcePatternResolver();
        SimpleMetadataReaderFactory simpleMetadataReaderFactory = new SimpleMetadataReaderFactory();

        ClassLoader classLoader = AuditDataContractTest.class.getClassLoader();

        List<Method> methods = new ArrayList<>();

        for (Resource resource : pathMatchingResourcePatternResolver.getResources(CLASS_PATTERN)) {
            URL url = resource.getURL();

            String externalForm = url.toExternalForm();

            if (externalForm.contains(COMPONENT_PACKAGE_PATH)) {
                continue;
            }

            MetadataReader metadataReader = simpleMetadataReaderFactory.getMetadataReader(resource);

            AnnotationMetadata annotationMetadata = metadataReader.getAnnotationMetadata();

            if (!annotationMetadata.hasAnnotatedMethods(Audited.class.getName())) {
                continue;
            }

            Class<?> auditedClass = Class.forName(annotationMetadata.getClassName(), false, classLoader);

            for (Method method : auditedClass.getDeclaredMethods()) {
                if (!method.isSynthetic() && method.isAnnotationPresent(Audited.class)) {
                    methods.add(method);
                }
            }
        }

        return methods;
    }

    private static List<Method> findGuardedMethods() throws IOException, ClassNotFoundException {
        PathMatchingResourcePatternResolver pathMatchingResourcePatternResolver =
            new PathMatchingResourcePatternResolver();
        SimpleMetadataReaderFactory simpleMetadataReaderFactory = new SimpleMetadataReaderFactory();

        ClassLoader classLoader = AuditDataContractTest.class.getClassLoader();

        String preAuthorizeName = PreAuthorize.class.getName();

        List<Method> methods = new ArrayList<>();

        for (Resource resource : pathMatchingResourcePatternResolver.getResources(CLASS_PATTERN)) {
            URL url = resource.getURL();

            String externalForm = url.toExternalForm();

            if (externalForm.contains(COMPONENT_PACKAGE_PATH)) {
                continue;
            }

            MetadataReader metadataReader = simpleMetadataReaderFactory.getMetadataReader(resource);

            AnnotationMetadata annotationMetadata = metadataReader.getAnnotationMetadata();

            boolean classGuarded = annotationMetadata.hasAnnotation(preAuthorizeName);

            if (annotationMetadata.isInterface() ||
                !(classGuarded || annotationMetadata.hasAnnotatedMethods(preAuthorizeName))) {

                continue;
            }

            String className = annotationMetadata.getClassName();

            if (className.endsWith("Controller")) {
                continue;
            }

            Class<?> guardedClass = Class.forName(className, false, classLoader);

            for (Method method : guardedClass.getDeclaredMethods()) {
                int modifiers = method.getModifiers();

                if (method.isSynthetic() || !Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) {
                    continue;
                }

                if (classGuarded || method.isAnnotationPresent(PreAuthorize.class)) {
                    methods.add(method);
                }
            }
        }

        return methods;
    }

    private static Map<String, Class<?>> getVariableTypes(Method method) {
        Map<String, Class<?>> variableTypes = new HashMap<>();

        for (Parameter parameter : method.getParameters()) {
            variableTypes.put(parameter.getName(), parameter.getType());
        }

        Class<?> returnType = method.getReturnType();

        if (returnType != void.class) {
            variableTypes.put(RESULT_VARIABLE, returnType);
        }

        return variableTypes;
    }

    private static boolean hasReadableProperty(Class<?> type, String propertyName) {
        String capitalizedName = StringUtils.capitalize(propertyName);

        Set<String> accessorNames = Set.of("get" + capitalizedName, "is" + capitalizedName, propertyName);

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 0 && accessorNames.contains(method.getName())) {
                return true;
            }
        }

        Field field;

        try {
            field = type.getField(propertyName);
        } catch (NoSuchFieldException noSuchFieldException) {
            return false;
        }

        return Modifier.isPublic(field.getModifiers());
    }

    private static boolean isVariable(SpelNode spelNode, String variableName) {
        String variableReference = spelNode.toStringAST();

        return variableReference.equals("#" + variableName);
    }

    private static SpelNode parse(String expressionString) {
        SpelExpression spelExpression = SPEL_EXPRESSION_PARSER.parseRaw(expressionString.strip());

        return spelExpression.getAST();
    }
}
