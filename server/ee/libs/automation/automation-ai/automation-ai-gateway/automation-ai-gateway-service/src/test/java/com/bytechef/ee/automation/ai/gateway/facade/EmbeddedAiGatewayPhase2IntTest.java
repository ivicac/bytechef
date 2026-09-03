/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.ee.automation.ai.gateway.config.AiGatewayIntTestConfiguration;
import com.bytechef.ee.automation.ai.gateway.service.AiGatewayIntTestConfigurationSharedMocks;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.gateway.facade.ConnectedUserAiGatewayRoutingPolicyFacade;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayEmbeddedSettings;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelDeployment;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewaySpendSummary;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionResponse;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayChatModelFactory;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayEmbeddedSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayModelDeploymentService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewaySpendService;
import com.bytechef.ee.platform.ai.llm.usage.AiLlmUsage;
import com.bytechef.ee.platform.ai.llm.usage.Money;
import com.bytechef.ee.platform.ai.llm.usage.service.AiLlmUsageService;
import com.bytechef.ee.platform.ai.model.catalog.domain.AiModel;
import com.bytechef.ee.platform.ai.model.catalog.repository.AiModelRepository;
import com.bytechef.ee.platform.ai.model.catalog.service.AiModelService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.domain.Property;
import com.bytechef.platform.configuration.service.PropertyService;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end coverage for embedded AI Gateway phase 2, against a real Postgres database rather than mocks, driving the
 * facade's actual public {@code chatCompletion} entry point rather than a package-private seam — the only way to prove
 * the full request pipeline (routing precedence, BYOK provider resolution, spend attribution) wires together the way
 * production traffic actually exercises it.
 *
 * <p>
 * {@code resolveAuthenticatedEnvironmentId()} defaults to {@link Environment#DEVELOPMENT}'s ordinal (0) for every call
 * in this test class, since none of them authenticate via {@code AiGatewayApiKeyAuthenticationToken} — the tenant-admin
 * {@link UsernamePasswordAuthenticationToken} set up in {@link #setUp()} exists only to satisfy
 * {@code validateWorkspaceAccess}'s fail-closed check, not to select an environment. Every fixture in this class is
 * therefore built against {@code Environment.DEVELOPMENT.ordinal()}.
 *
 * <p>
 * {@link PropertyService} stays a {@link AiGatewayIntTestConfigurationSharedMocks Mockito mock}, exactly as it is for
 * {@code EmbeddedAiGatewayIntTest} and every other test in this module: the embedded-settings row is produced by
 * round-tripping through the real {@link AiGatewayEmbeddedSettingsService#upsert} write path and handing the captured
 * value back out of a stubbed {@link PropertyService#fetchProperty}, avoiding a hard dependency on
 * {@code platform-configuration-service}'s credential-store beans.
 *
 * <p>
 * {@link AiGatewayChatModelFactory} also stays a shared mock (the module has no real LLM credentials to call out with)
 * — its stubbed {@link ChatModel} returns a canned response regardless of which {@link AiGatewayProvider} it was built
 * from, so every assertion about routing/BYOK precedence in this class is made by inspecting <em>which provider</em>
 * the factory was invoked with, or which routing policy name ended up on the response's
 * {@link AiGatewayChatCompletionResponse.GatewayMetadata}, never by inspecting the canned completion text itself.
 *
 * @version ee
 */
@ActiveProfiles("testint")
@SpringBootTest(classes = AiGatewayIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
@AiGatewayIntTestConfigurationSharedMocks
class EmbeddedAiGatewayPhase2IntTest {

    @Autowired
    private AiGatewayEmbeddedSettingsService aiGatewayEmbeddedSettingsService;

    @Autowired
    private AiGatewayFacadeImpl aiGatewayFacadeImpl;

    @Autowired
    private AiGatewayModelDeploymentService aiGatewayModelDeploymentService;

    @Autowired
    private AiModelRepository aiModelRepository;

    @Autowired
    private AiGatewayProviderService aiGatewayProviderService;

    @Autowired
    private AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;

    @Autowired
    private AiGatewaySpendService aiGatewaySpendService;

    @Autowired
    private AiLlmUsageService aiLlmUsageService;

    @Autowired
    private AiModelService aiModelService;

    @Autowired
    private ConnectedUserAiGatewayRoutingPolicyFacade connectedUserAiGatewayRoutingPolicyFacade;

    @Autowired
    private ConnectedUserService connectedUserService;

    @Autowired
    private AiGatewayChatModelFactory aiGatewayChatModelFactory;

    @Autowired
    private PropertyService propertyService;

    @MockitoBean
    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        // Fail-closed per validateWorkspaceAccess's own contract: a tenant-admin bypass keeps every test in this class
        // focused on gateway/routing/BYOK behavior rather than on authorization rules, which are covered elsewhere
        // (PermissionEvaluatorWiringIntTest and friends).
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(
                "phase2-test-admin", "password", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        org.mockito.Mockito.lenient()
            .when(permissionService.isTenantAdmin())
            .thenReturn(true);

        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        org.mockito.Mockito.lenient()
            .when(aiGatewayChatModelFactory.getChatModel(any()))
            .thenReturn(chatModel);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The full resolution chain — request-specified &gt; connected-user &gt; model default &gt; embedded default —
     * proven by removing/disabling one level at a time and re-issuing the identical request. Stage 2 is the specific
     * gap {@code EmbeddedAiGatewayIntTest}'s own phase-1/2 coverage could not close (per the task-7 brief): the model
     * here carries a real default, so disabling the connected-user policy must land on the MODEL default, not skip
     * straight to the embedded default.
     */
    @Test
    void testRoutingPolicyPrecedenceCascadesThroughAllFourLevels() {
        long workspaceId = 920_101L;
        long environmentId = Environment.DEVELOPMENT.ordinal();

        AiGatewayProvider provider = createEnabledProvider("chain-tenant-anthropic", AiGatewayProviderType.ANTHROPIC);
        AiModel model = createModel(provider.getId(), "chain-gpt-4");

        AiGatewayRoutingPolicy connectedUserPolicy =
            createPolicyWithDeployment("chain-connected-user-policy", model.getId());
        AiGatewayRoutingPolicy modelDefaultPolicy =
            createPolicyWithDeployment("chain-model-default-policy", model.getId());
        AiGatewayRoutingPolicy embeddedDefaultPolicy =
            createPolicyWithDeployment("chain-embedded-default-policy", model.getId());
        AiGatewayRoutingPolicy explicitPolicy =
            createPolicyWithDeployment("chain-explicit-policy", model.getId());

        // Final whole-branch review, I-4: AiModelService#update now DOES copy defaultRoutingPolicyId through
        // applyAndSave (previously it silently dropped it, making the field settable once and then permanently
        // unchangeable). The repository is still used directly here rather than the service, though, the same way
        // the coordinator's ruling already sanctions calling AiGatewayEmbeddedSettingsService#upsert and
        // AiGatewayProviderService#updateConnectedUserId directly where no facade/admin surface exists -- this test
        // predates that fix and there is no reason to route a same-module test through the full service layer just
        // to set one column.
        model.setDefaultRoutingPolicyId(modelDefaultPolicy.getId());
        aiModelRepository.save(model);

        ConnectedUser connectedUser = connectedUserService.createConnectedUser("chain-user", environmentId);

        connectedUserAiGatewayRoutingPolicyFacade.bind(connectedUserPolicy.getId(), connectedUser.getId());
        stubEmbeddedSettings(environmentId, embeddedDefaultPolicy.getId());

        AiGatewayChatCompletionRequest baseRequest = buildRequest("anthropic/chain-gpt-4", null, workspaceId);

        // Stage 1: connected-user policy is enabled -- it must win over both the model default and the embedded
        // default, even though both of those are configured and available.
        AiGatewayChatCompletionResponse connectedUserWins =
            aiGatewayFacadeImpl.chatCompletion(baseRequest, null, null, connectedUser.getId());

        assertThat(connectedUserWins.gatewayMetadata()
            .routingPolicy())
                .as("An enabled connected-user policy must outrank both the model default and the embedded default")
                .isEqualTo(connectedUserPolicy.getName());

        // Stage 2: disable the connected-user policy. It must fall through to the MODEL default -- not skip past it to
        // the embedded default, which is the exact distinction Task 2's own test could not make (its model carried no
        // default at all).
        AiGatewayRoutingPolicy reloadedConnectedUserPolicy =
            aiGatewayRoutingPolicyService.getRoutingPolicy(connectedUserPolicy.getId());

        reloadedConnectedUserPolicy.setEnabled(false);
        aiGatewayRoutingPolicyService.update(reloadedConnectedUserPolicy);

        AiGatewayChatCompletionResponse modelDefaultWins =
            aiGatewayFacadeImpl.chatCompletion(baseRequest, null, null, connectedUser.getId());

        assertThat(modelDefaultWins.gatewayMetadata()
            .routingPolicy())
                .as("A DISABLED connected-user policy must fall through to the model default, not skip past it to "
                    + "the embedded default")
                .isEqualTo(modelDefaultPolicy.getName());

        // Stage 3: clear the model's own default too. With neither a connected-user policy nor a model default
        // available, the embedded default must win.
        AiModel reloadedModel = aiModelService.getModel(model.getId());

        reloadedModel.setDefaultRoutingPolicyId(null);
        aiModelRepository.save(reloadedModel);

        AiGatewayChatCompletionResponse embeddedDefaultWins =
            aiGatewayFacadeImpl.chatCompletion(baseRequest, null, null, connectedUser.getId());

        assertThat(embeddedDefaultWins.gatewayMetadata()
            .routingPolicy())
                .as("With neither a connected-user policy nor a model default, the embedded default must win")
                .isEqualTo(embeddedDefaultPolicy.getName());

        // Stage 4: a request-specified routing policy must win over everything, even while the embedded default is
        // still configured and would otherwise apply.
        AiGatewayChatCompletionRequest explicitRequest =
            buildRequest("anthropic/chain-gpt-4", explicitPolicy.getName(), workspaceId);

        AiGatewayChatCompletionResponse explicitWins =
            aiGatewayFacadeImpl.chatCompletion(explicitRequest, null, null, connectedUser.getId());

        assertThat(explicitWins.gatewayMetadata()
            .routingPolicy())
                .as("A request-specified routing policy must win over every fallback level")
                .isEqualTo(explicitPolicy.getName());
    }

    @Test
    void testConnectedUsersOwnProviderIsChosenOverTheTenantProviderOnTheDirectPath() {
        long workspaceId = 920_201L;
        long environmentId = Environment.DEVELOPMENT.ordinal();

        AiGatewayProvider tenantProvider =
            createEnabledProvider("byok-tenant-azure-openai", AiGatewayProviderType.AZURE_OPENAI);
        createModel(tenantProvider.getId(), "byok-gpt-4");

        ConnectedUser connectedUser = connectedUserService.createConnectedUser("byok-user", environmentId);

        AiGatewayProvider connectedUserProvider =
            createEnabledProvider("byok-customer-azure-openai", AiGatewayProviderType.AZURE_OPENAI);

        aiGatewayProviderService.updateConnectedUserId(connectedUserProvider.getId(), connectedUser.getId());

        AiGatewayChatCompletionRequest request = buildRequest("azure-openai/byok-gpt-4", null, workspaceId);

        aiGatewayFacadeImpl.chatCompletion(request, null, null, connectedUser.getId());

        ArgumentCaptor<AiGatewayProvider> providerCaptor = ArgumentCaptor.forClass(AiGatewayProvider.class);

        verify(aiGatewayChatModelFactory).getChatModel(providerCaptor.capture());

        assertThat(providerCaptor.getValue()
            .getId())
                .as("The connected user's own BYOK provider must be chosen over the tenant's shared provider")
                .isEqualTo(connectedUserProvider.getId());
    }

    @Test
    void testConnectedUserWithNoOwnProviderFallsBackToTheTenantProvider() {
        long workspaceId = 920_202L;
        long environmentId = Environment.DEVELOPMENT.ordinal();

        AiGatewayProvider tenantProvider =
            createEnabledProvider("byok-fallback-tenant-cohere", AiGatewayProviderType.COHERE);
        createModel(tenantProvider.getId(), "byok-fallback-gpt-4");

        ConnectedUser connectedUser = connectedUserService.createConnectedUser("byok-fallback-user", environmentId);

        AiGatewayChatCompletionRequest request = buildRequest("cohere/byok-fallback-gpt-4", null, workspaceId);

        aiGatewayFacadeImpl.chatCompletion(request, null, null, connectedUser.getId());

        ArgumentCaptor<AiGatewayProvider> providerCaptor = ArgumentCaptor.forClass(AiGatewayProvider.class);

        verify(aiGatewayChatModelFactory).getChatModel(providerCaptor.capture());

        assertThat(providerCaptor.getValue()
            .getId())
                .as("A connected user with no BYOK provider of their own must fall back to the tenant's provider")
                .isEqualTo(tenantProvider.getId());
    }

    /**
     * A single tenant's schema can never actually contain another tenant's row, so an id belonging to another tenant
     * and an id that was simply never created are, by construction, the same case from inside this schema — the same
     * reasoning {@link ConnectedUserAiGatewayRoutingPolicyFacade}'s own Javadoc already documents for binding. This
     * test proves the same equivalence for the provider lookup and the spend query: a made-up id stands in for a
     * cross-tenant id because no code path inside a tenant's own schema can ever tell the two apart.
     */
    @Test
    void testUnknownIdsAreIndistinguishableFromMissingForPolicyBindingProviderAndSpend() {
        long environmentId = Environment.DEVELOPMENT.ordinal();
        long unknownId = 999_888_777L;

        AiGatewayRoutingPolicy policy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("cross-tenant-policy", AiGatewayRoutingStrategyType.SIMPLE));
        ConnectedUser connectedUser = connectedUserService.createConnectedUser("cross-tenant-user", environmentId);

        // Policy: an unknown routing policy id and an unknown connected user id both fail the same way -- a plain
        // IllegalArgumentException naming what was not found, never a different exception type or a leak of which
        // case applied.
        assertThatThrownBy(() -> connectedUserAiGatewayRoutingPolicyFacade.bind(unknownId, connectedUser.getId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Routing policy not found");

        assertThatThrownBy(() -> connectedUserAiGatewayRoutingPolicyFacade.bind(policy.getId(), unknownId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Connected user not found");

        // Provider: an unknown connected user id resolves to Optional.empty(), exactly like a real connected user
        // that simply has no BYOK provider of its own -- never a different outcome (an exception, a leaked row).
        Optional<AiGatewayProvider> providerForUnknownId =
            aiGatewayProviderService.fetchProviderByConnectedUserIdAndType(
                unknownId, AiGatewayProviderType.OPENAI);
        Optional<AiGatewayProvider> providerForRealUserWithNoBYOK =
            aiGatewayProviderService.fetchProviderByConnectedUserIdAndType(
                connectedUser.getId(), AiGatewayProviderType.OPENAI);

        assertThat(providerForUnknownId).isEmpty();
        assertThat(providerForRealUserWithNoBYOK).isEmpty();

        // Spend: an unknown connected user id sums to zero, exactly like a real connected user with no recorded
        // spend -- never an exception, never another connected user's spend leaking through.
        Instant periodStart = Instant.now()
            .minus(1, ChronoUnit.HOURS);
        Instant periodEnd = Instant.now()
            .plus(1, ChronoUnit.HOURS);

        Money spendForUnknownId =
            aiGatewaySpendService.getTotalCostByConnectedUserId(unknownId, periodStart, periodEnd);
        Money spendForRealUserWithNoRows =
            aiGatewaySpendService.getTotalCostByConnectedUserId(connectedUser.getId(), periodStart, periodEnd);

        assertThat(spendForUnknownId).isEqualTo(Money.usd(BigDecimal.ZERO));
        assertThat(spendForRealUserWithNoRows).isEqualTo(Money.usd(BigDecimal.ZERO));
    }

    @Test
    void testChatCompletionAttributesTheCreatedUsageRowToTheResolvedConnectedUserAndAutomationStaysNull() {
        long workspaceId = 920_301L;
        long environmentId = Environment.DEVELOPMENT.ordinal();

        AiGatewayProvider provider =
            createEnabledProvider("spend-write-tenant-deepseek", AiGatewayProviderType.DEEPSEEK);
        createModel(provider.getId(), "spend-write-gpt-4");

        ConnectedUser connectedUser = connectedUserService.createConnectedUser("spend-write-user", environmentId);

        AiGatewayChatCompletionRequest request = buildRequest("deepseek/spend-write-gpt-4", null, workspaceId);

        aiGatewayFacadeImpl.chatCompletion(request, null, null, connectedUser.getId());
        aiGatewayFacadeImpl.chatCompletion(request, null, null, null);

        Instant start = Instant.now()
            .minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now()
            .plus(1, ChronoUnit.HOURS);

        List<AiLlmUsage> usages = aiLlmUsageService.getRequestLogsByWorkspace(workspaceId, start, end);

        assertThat(usages)
            .as("Both requests in this test must have written a usage row")
            .hasSize(2);

        assertThat(usages)
            .as("The embedded request's usage row must carry the resolved connected user id")
            .anySatisfy(usage -> assertThat(usage.getUserId()).isEqualTo(connectedUser.getId()));

        assertThat(usages)
            .as("The automation request's usage row (null connectedUserId) must keep userId null -- automation "
                + "behavior must not change")
            .anySatisfy(usage -> assertThat(usage.getUserId()).isNull());
    }

    @Test
    void testSpendSummaryConnectedUserIdRoundTripsAndPreExistingRowsWithoutItStayNull() {
        long workspaceId = 920_302L;
        long environmentId = Environment.DEVELOPMENT.ordinal();

        ConnectedUser connectedUser = connectedUserService.createConnectedUser("spend-summary-user", environmentId);

        Instant periodStart = Instant.now()
            .minus(1, ChronoUnit.HOURS);
        Instant periodEnd = Instant.now();

        // Simulates a summary row written before this column existed -- a plain workspace rollup row with no
        // connected user attribution at all.
        AiGatewaySpendSummary legacyRow = new AiGatewaySpendSummary(periodStart, periodEnd);

        legacyRow.setProvider("openai");
        legacyRow.setModel("legacy-model");
        legacyRow.setTotalCost(BigDecimal.valueOf(3));
        legacyRow.setWorkspaceId(workspaceId);

        AiGatewaySpendSummary legacySaved = aiGatewaySpendService.create(legacyRow);

        // Simulates a row a post-phase-2 rollup would write: the same shape, but attributed to a connected user.
        AiGatewaySpendSummary newRow = new AiGatewaySpendSummary(periodStart, periodEnd);

        newRow.setProvider("openai");
        newRow.setModel("new-model");
        newRow.setTotalCost(BigDecimal.valueOf(5));
        newRow.setWorkspaceId(workspaceId);
        newRow.setConnectedUserId(connectedUser.getId());

        aiGatewaySpendService.create(newRow);

        List<AiGatewaySpendSummary> summaries =
            aiGatewaySpendService.getSpendSummariesByWorkspaceId(workspaceId, periodStart, periodEnd.plusSeconds(1));

        assertThat(summaries)
            .as("Both rows must be present")
            .hasSize(2);

        AiGatewaySpendSummary reloadedLegacyRow = summaries.stream()
            .filter(summary -> summary.getId()
                .equals(legacySaved.getId()))
            .findFirst()
            .orElseThrow();

        assertThat(reloadedLegacyRow.getConnectedUserId())
            .as("A row written before connected-user attribution existed must stay null -- it is never "
                + "retroactively populated")
            .isNull();

        Money totalForConnectedUser = aiGatewaySpendService.getTotalCostByConnectedUserId(
            connectedUser.getId(), periodStart, periodEnd.plusSeconds(1));

        assertThat(totalForConnectedUser)
            .as("The connected-user spend total must count only the row attributed to them, not the "
                + "null-attributed legacy row")
            .isEqualTo(Money.usd(BigDecimal.valueOf(5)));
    }

    /**
     * Each caller must pass a {@link AiGatewayProviderType} used by no other test method in this class.
     * {@code resolveTenantProviderByTypeName} (behind the direct-routing path and {@code calculateTraceCost}'s
     * trace-cost lookup, both hit on every {@code chatCompletion} call regardless of routing path) resolves "the tenant
     * provider" purely by type, with no other scoping available at this layer — so two test methods sharing a type
     * would nondeterministically resolve to whichever one happened to insert its provider row first across the whole
     * class run, not necessarily the one that created it.
     */
    private AiGatewayProvider createEnabledProvider(String name, AiGatewayProviderType type) {
        return aiGatewayProviderService.create(
            new AiGatewayProvider(name, type, "sk-test-" + UUID.randomUUID()));
    }

    private AiModel createModel(long providerId, String name) {
        AiModel model = new AiModel(providerId, name);

        model.setInputCostPerMTokens(BigDecimal.ONE);
        model.setOutputCostPerMTokens(BigDecimal.ONE);

        return aiModelService.create(model);
    }

    private AiGatewayRoutingPolicy createPolicyWithDeployment(String policyName, long modelId) {
        AiGatewayRoutingPolicy policy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy(policyName, AiGatewayRoutingStrategyType.SIMPLE));

        AiGatewayModelDeployment deployment = new AiGatewayModelDeployment(policy.getId(), modelId);

        aiGatewayModelDeploymentService.create(deployment);

        return policy;
    }

    private AiGatewayChatCompletionRequest buildRequest(String model, String routingPolicyName, long workspaceId) {
        return new AiGatewayChatCompletionRequest(
            model, List.of(new AiGatewayChatMessage(AiGatewayChatRole.USER, "Hello")), null, null, null, false,
            routingPolicyName, null, null, null, Map.of("workspace_id", String.valueOf(workspaceId)));
    }

    @SuppressWarnings("unchecked")
    private void stubEmbeddedSettings(long environmentId, Long defaultRoutingPolicyId) {
        aiGatewayEmbeddedSettingsService.upsert(
            new AiGatewayEmbeddedSettings(
                environmentId, null, null, null, null, null, defaultRoutingPolicyId, null, null));

        ArgumentCaptor<Map<String, Object>> valueCaptor = ArgumentCaptor.forClass(Map.class);

        verify(propertyService).save(
            eq(AiGatewayEmbeddedSettings.PROPERTY_KEY), valueCaptor.capture(), eq(Property.Scope.EMBEDDED),
            isNull(), eq(environmentId));

        Property property = new Property();

        property.setValue(valueCaptor.getValue());
        property.setEnvironment((int) environmentId);

        when(
            propertyService.fetchProperty(
                AiGatewayEmbeddedSettings.PROPERTY_KEY, Property.Scope.EMBEDDED, null, environmentId))
                    .thenReturn(Optional.of(property));
    }

    private ChatResponse mockChatResponse() {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = new AssistantMessage("Hello");

        when(generation.getOutput()).thenReturn(assistantMessage);
        when(generation.getMetadata()).thenReturn(
            ChatGenerationMetadata.builder()
                .finishReason("stop")
                .build());
        when(chatResponse.getResult()).thenReturn(generation);
        when(chatResponse.getMetadata()).thenReturn(mock(ChatResponseMetadata.class));

        return chatResponse;
    }
}
