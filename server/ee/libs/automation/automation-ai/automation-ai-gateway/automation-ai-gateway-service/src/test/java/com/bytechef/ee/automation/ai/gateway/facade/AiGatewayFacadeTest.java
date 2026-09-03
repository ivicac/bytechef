/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.ee.automation.ai.gateway.budget.AiGatewayBudgetChecker;
import com.bytechef.ee.automation.ai.gateway.budget.AiGatewayBudgetChecker.BudgetCheckResult;
import com.bytechef.ee.automation.ai.gateway.evaluation.AiEvalExecutor;
import com.bytechef.ee.automation.ai.gateway.ratelimit.AiGatewayRateLimitChecker;
import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewayProjectService;
import com.bytechef.ee.automation.ai.observability.service.WorkspaceAiObservabilitySessionService;
import com.bytechef.ee.automation.ai.observability.service.WorkspaceAiObservabilityTraceService;
import com.bytechef.ee.automation.ai.prompt.service.WorkspaceAiPromptService;
import com.bytechef.ee.platform.ai.gateway.cache.AiGatewayResponseCache;
import com.bytechef.ee.platform.ai.gateway.compression.AiGatewayContextCompressor;
import com.bytechef.ee.platform.ai.gateway.cost.AiGatewayCostCalculator;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayConnectedUserSettings;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayEmbeddedSettings;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelDeployment;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderScopeViolationException;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.domain.BudgetExceededException;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionResponse;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayContentBlock;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayContentBlockType;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayEmbeddingRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayEmbeddingResponse;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayChatModelFactory;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayEmbeddingModelFactory;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayProviderResolver;
import com.bytechef.ee.platform.ai.gateway.reliability.AiGatewayRetryHandler;
import com.bytechef.ee.platform.ai.gateway.routing.AiGatewayRouter;
import com.bytechef.ee.platform.ai.gateway.routing.AiGatewayRoutingContext;
import com.bytechef.ee.platform.ai.gateway.routing.PromptComplexityScorer;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayConnectedUserSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayEmbeddedSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayModelDeploymentService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewaySpendService;
import com.bytechef.ee.platform.ai.llm.usage.AiLlmUsage;
import com.bytechef.ee.platform.ai.llm.usage.Money;
import com.bytechef.ee.platform.ai.llm.usage.service.AiLlmUsageService;
import com.bytechef.ee.platform.ai.model.catalog.domain.AiModel;
import com.bytechef.ee.platform.ai.model.catalog.service.AiModelService;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilitySpan;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityTrace;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityTraceSource;
import com.bytechef.ee.platform.ai.observability.facade.AiObservabilityTracingHeaders;
import com.bytechef.ee.platform.ai.observability.service.AiObservabilitySessionService;
import com.bytechef.ee.platform.ai.observability.service.AiObservabilitySpanService;
import com.bytechef.ee.platform.ai.observability.service.AiObservabilityTraceService;
import com.bytechef.ee.platform.ai.prompt.AiPromptVersionService;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiToken;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import com.bytechef.platform.security.web.authentication.AbstractApiKeyAuthenticationToken;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * @author Ivica Cardic
 * @version ee
 */
@ExtendWith(MockitoExtension.class)
class AiGatewayFacadeTest {

    private static final long ENVIRONMENT_ID = 1L;

    @Mock
    private AiEvalExecutor aiEvalExecutor;

    @Mock
    private AiGatewayBudgetChecker aiGatewayBudgetChecker;

    @Mock
    private AiGatewayChatModelFactory aiGatewayChatModelFactory;

    @Mock
    private AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;

    @Mock
    private AiGatewayContextCompressor aiGatewayContextCompressor;

    @Mock
    private AiGatewayRateLimitChecker aiGatewayRateLimitChecker;

    @Mock
    private AiGatewayCostCalculator aiGatewayCostCalculator;

    @Mock
    private AiGatewayEmbeddingModelFactory aiGatewayEmbeddingModelFactory;

    @Mock
    private AiGatewayModelDeploymentService aiGatewayModelDeploymentService;

    @Mock
    private AiModelService aiModelService;

    @Mock
    private WorkspaceAiGatewayProjectService workspaceAiGatewayProjectService;

    @Mock
    private AiGatewayProviderService aiGatewayProviderService;

    @Mock
    private AiGatewayProviderResolver aiGatewayProviderResolver;

    @Mock
    private AiLlmUsageService aiGatewayRequestLogService;

    @Mock
    private AiGatewayResponseCache aiGatewayResponseCache;

    @Mock
    private AiGatewayRetryHandler aiGatewayRetryHandler;

    @Mock
    private AiGatewayRouter aiGatewayRouter;

    @Mock
    private AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;

    @Mock
    private AiGatewaySpendService aiGatewaySpendService;

    @Mock
    private PromptComplexityScorer promptComplexityScorer;

    @Mock
    private WorkspaceAiPromptService workspaceAiPromptService;

    @Mock
    private AiPromptVersionService aiPromptVersionService;

    @Mock
    private AiObservabilitySessionService aiObservabilitySessionService;

    @Mock
    private WorkspaceAiObservabilitySessionService workspaceAiObservabilitySessionService;

    @Mock
    private AiObservabilitySpanService aiObservabilitySpanService;

    @Mock
    private AiObservabilityTraceService aiObservabilityTraceService;

    @Mock
    private WorkspaceAiObservabilityTraceService workspaceAiObservabilityTraceService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private PermissionService permissionService;

    @Mock
    private AiGatewayEmbeddedSettingsService embeddedSettingsService;

    @Mock
    private ObjectProvider<AiGatewayEmbeddedSettingsService> embeddedSettingsServiceProvider;

    private AiGatewayFacade aiGatewayFacade;

    @BeforeEach
    void setUp() {
        // Seed a tenant-admin SecurityContext so validateWorkspaceAccess passes — tests don't exercise auth rules,
        // they exercise gateway behavior, so an admin bypass keeps the tests focused on the actual subject under test.
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-admin", "password", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        // lenient() — isTenantAdmin is read only on requests that actually hit validateWorkspaceAccess; tests that
        // short-circuit before reaching it (e.g. missing-tag validation) don't exercise this stub.
        org.mockito.Mockito.lenient()
            .when(permissionService.isTenantAdmin())
            .thenReturn(true);

        // lenient() — most tests never reach resolveEmbeddedDefaultRoutingPolicyId at all: they call one of the
        // pre-existing chatCompletion/chatCompletionStream overloads, which pass a null connectedUserId and
        // short-circuit before the provider is read. This is the happy-path default for the tests that call the
        // package-private method directly (or the 4-/5-argument overloads with a real connectedUserId).
        org.mockito.Mockito.lenient()
            .when(embeddedSettingsServiceProvider.getIfAvailable())
            .thenReturn(embeddedSettingsService);

        // aiGatewayProviderResolver is mocked (AiGatewayProviderResolverImpl is package-private in a different
        // package, so the real implementation cannot be constructed here) — these two lenient defaults reproduce its
        // production behavior in terms of the already-mocked aiGatewayProviderService, so every pre-existing test's
        // aiGatewayProviderService stubbing (getProvider/getEnabledProviders/fetchProviderByConnectedUserIdAndType)
        // continues to determine the outcome exactly as it did before BYOK resolution existed, with no per-test
        // changes. Tests that actually exercise BYOK precedence override these with their own stubbing.
        org.mockito.Mockito.lenient()
            .when(aiGatewayProviderResolver.resolveTenantProvider(any()))
            .thenAnswer(invocation -> {
                AiGatewayProviderType type = invocation.getArgument(0);

                return aiGatewayProviderService.getEnabledProviders()
                    .stream()
                    .filter(provider -> provider.getConnectedUserId() == null)
                    .filter(provider -> provider.getType() == type)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No enabled provider found for type: " + type));
            });
        org.mockito.Mockito.lenient()
            .when(aiGatewayProviderResolver.resolve(anyLong(), any()))
            .thenAnswer(invocation -> {
                long connectedUserId = invocation.getArgument(0);
                AiGatewayProviderType type = invocation.getArgument(1);

                return aiGatewayProviderService.fetchProviderByConnectedUserIdAndType(connectedUserId, type)
                    .filter(AiGatewayProvider::isEnabled)
                    .orElseGet(() -> aiGatewayProviderResolver.resolveTenantProvider(type));
            });

        aiGatewayFacade = buildFacade(guardrails(false));
    }

    private AiGatewayFacadeImpl buildFacade(
        com.bytechef.ee.automation.ai.gateway.guardrail.AiGatewayGuardrails guardrails) {

        return new AiGatewayFacadeImpl(
            aiEvalExecutor, aiGatewayBudgetChecker, aiGatewayRateLimitChecker,
            aiGatewayChatModelFactory, aiGatewayConnectedUserSettingsService, aiGatewayContextCompressor,
            aiGatewayCostCalculator,
            guardrails,
            aiGatewayEmbeddingModelFactory, aiGatewayModelDeploymentService,
            aiModelService, workspaceAiGatewayProjectService, aiGatewayProviderService,
            aiGatewayProviderResolver,
            aiGatewayRequestLogService, aiGatewayResponseCache, aiGatewayRetryHandler,
            aiGatewayRouter, aiGatewayRoutingPolicyService, aiGatewaySpendService, promptComplexityScorer,
            workspaceAiPromptService,
            aiPromptVersionService, aiObservabilitySessionService,
            workspaceAiObservabilitySessionService, workspaceAiObservabilityTraceService,
            aiObservabilitySpanService, aiObservabilityTraceService,
            mock(com.bytechef.platform.tag.service.TagService.class),
            mock(com.bytechef.ee.automation.ai.gateway.service.AiGatewayWorkspaceSettingsService.class),
            mock(com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService.class),
            emptyMetricsProvider(),
            embeddedSettingsServiceProvider,
            new org.springframework.context.support.GenericApplicationContext(),
            permissionService,
            transactionManager);
    }

    private static com.bytechef.ee.automation.ai.gateway.guardrail.AiGatewayGuardrails guardrails(
        boolean responseScanEnabled) {

        com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService settingsService =
            mock(com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService.class);

        // PII redaction is enabled here (unlike secrets) so that the response-scan direction, when
        // responseScanEnabled is true, actually has a category to redact -- response scanning now honours the
        // workspace's category switches instead of always redacting every kind.
        com.bytechef.ee.platform.ai.guardrails.AiGuardrails aiGuardrails =
            new com.bytechef.ee.platform.ai.guardrails.AiGuardrails(
                settingsService, null, null, null, true, false, "", false, false, responseScanEnabled, false);

        return new com.bytechef.ee.automation.ai.gateway.guardrail.AiGatewayGuardrails(
            aiGuardrails, null, settingsService, null, null, null, false, false);
    }

    private static com.bytechef.ee.automation.ai.gateway.guardrail.AiGatewayGuardrails
        guardrailsWithPiiTokenizationAndResponseScan() {

        com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService settingsService =
            mock(com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService.class);

        com.bytechef.ee.platform.ai.guardrails.AiGuardrails aiGuardrails =
            new com.bytechef.ee.platform.ai.guardrails.AiGuardrails(
                settingsService, null, null, null, true, false, "", false, false, true, false);

        return new com.bytechef.ee.automation.ai.gateway.guardrail.AiGatewayGuardrails(
            aiGuardrails, null, settingsService, null, null, null, false, false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private static
        ObjectProvider<com.bytechef.ee.platform.ai.gateway.metrics.AiGatewayMetrics>
        emptyMetricsProvider() {

        ObjectProvider<com.bytechef.ee.platform.ai.gateway.metrics.AiGatewayMetrics> provider =
            (ObjectProvider<com.bytechef.ee.platform.ai.gateway.metrics.AiGatewayMetrics>) mock(
                ObjectProvider.class);

        // lenient() — some tests never reach a request-log catch site and Mockito strict mode flags the stubbing
        // as "unnecessary" even though it's semantically required by the production helper
        // recordRequestLogPersistFailure.
        org.mockito.Mockito.lenient()
            .when(provider.getIfAvailable())
            .thenReturn(null);

        return provider;
    }

    @Test
    void testChatCompletionThrowsWhenTagsMissingWorkspaceId() {
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, null, null, null, null, Map.of());

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> aiGatewayFacade.chatCompletion(request,
                new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        assertEquals(
            "Request for model 'openai/gpt-4' is missing required 'workspace_id' tag", exception.getMessage());
    }

    @Test
    void testChatCompletionThrowsWhenTagsAreNull() {
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, null, null);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> aiGatewayFacade.chatCompletion(request,
                new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        assertEquals(
            "Request for model 'openai/gpt-4' is missing required 'workspace_id' tag", exception.getMessage());
    }

    @Test
    void testChatCompletionThrowsWhenWorkspaceIdNotANumber() {
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, null, null, null, null, Map.of("workspace_id", "not-a-number"));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> aiGatewayFacade.chatCompletion(request,
                new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        assertEquals("Invalid workspace_id tag: not-a-number", exception.getMessage());
    }

    @Test
    void testChatCompletionThrowsWhenBudgetExceeded() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(
            BudgetCheckResult.rejected(new BigDecimal("110"), new BigDecimal("100"), BigDecimal.valueOf(110)));

        BudgetExceededException exception = assertThrows(
            BudgetExceededException.class, () -> aiGatewayFacade.chatCompletion(request,
                new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        assertNotNull(exception.getMessage());
        assertEquals(
            "Budget limit exceeded for workspace 1. Current spend: $110 / Budget: $100",
            exception.getMessage());
    }

    @Test
    void testResolveModelThrowsForMalformedModelString() {
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "gpt-4-no-slash", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, null, null, null, null, Map.of("workspace_id", "1"));

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> aiGatewayFacade.chatCompletion(request,
                new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        assertEquals("Model must be in format 'provider/model', got: gpt-4-no-slash", exception.getMessage());
    }

    @Test
    void testResolveModelThrowsWhenProviderTypeNotFound() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);
        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of());

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> aiGatewayFacade.chatCompletion(request,
                new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        assertEquals("No enabled provider found for type: OPENAI", exception.getMessage());
    }

    @Test
    void testChatCompletionDirectSuccess() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);
        assertEquals("openai/gpt-4", response.model());
        assertEquals(1, response.choices()
            .size());
        assertEquals("Hello", response.choices()
            .get(0)
            .message()
            .content());

        verify(aiGatewayRequestLogService).create(any(), any());
    }

    @Test
    void testChatCompletionRedactsResponseWhenResponseScanningEnabled() {
        AiGatewayFacade facade = buildFacade(guardrails(true));

        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        // mockChatResponse() itself stubs mocks, so it must be built before when(...) opens a stubbing, otherwise
        // Mockito reports an UnfinishedStubbingException for the nested stubbing.
        ChatResponse chatResponse = mockChatResponse("Contact bob@acme.io");

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = facade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertEquals("Contact [REDACTED_EMAIL_ADDRESS]", response.choices()
            .get(0)
            .message()
            .content());
    }

    /**
     * End-to-end proof that the facade threads one token session across both guardrail calls in {@code chatCompletion}:
     * the outbound prompt carries a session token (not an irreversible {@code [REDACTED_EMAIL_ADDRESS]} placeholder)
     * for the tokenized PII, and once the "model" echoes that token back in its completion, the facade's response
     * carries the real value again — restored, not left as a token and not re-redacted. A reversed scan-then-restore
     * order in {@code AiGatewayGuardrails.redactResponse} would show up here as the assertion failing with
     * {@code [REDACTED_EMAIL_ADDRESS]} in place of the real address (see
     * {@code AiGatewayGuardrailsTest.testRedactResponseWithSessionScansBeforeRestoring} for that same failure mode
     * proven directly against the adapter).
     */
    @Test
    void testChatCompletionTokenizesRequestAndRestoresRoundTrippedTokenInResponse() {
        AiGatewayFacade facade = buildFacade(guardrailsWithPiiTokenizationAndResponseScan());

        Map<String, String> tags = Map.of("workspace_id", "1");
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Contact bob@acme.io")),
            null, null, null, false, null, null, null, null, tags);

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt sentPrompt = invocation.getArgument(0);
            String sentText = sentPrompt.getInstructions()
                .stream()
                .map(Message::getText)
                .collect(Collectors.joining(" "));

            Matcher matcher = PiiToken.pattern()
                .matcher(sentText);

            assertTrue(matcher.find(), "expected the sent prompt to carry a PII token, got: " + sentText);

            return mockChatResponse("Sure, reaching out to " + matcher.group() + " shortly.");
        });

        AiGatewayChatCompletionResponse response = facade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertEquals("Sure, reaching out to bob@acme.io shortly.", response.choices()
            .get(0)
            .message()
            .content());
    }

    /**
     * C1 regression test: the observability span persisted for this exchange must record the response the way the model
     * actually produced it (scanned, but not yet restored), never the real PII value {@code chatCompletion} restores
     * afterward for the CALLER. Before the fix, {@code processTracingHeaders} ran on the already-restored response, so
     * {@code span.getOutput()} carried the real address, permanently persisting into the span store exactly the PII
     * value the workspace's "Redact PII" setting exists to keep out of it — even though the same setting already made
     * the trace row's output a SHA-256 digest, the span row was never digested at all. This proves the span instead
     * carries this exchange's own {@code [PII_*]} token, and that the caller-visible response still gets the real value
     * restored regardless.
     */
    @Test
    void testChatCompletionTracesSpanWithScannedNotRestoredOutput() {
        AiGatewayFacade facade = buildFacade(guardrailsWithPiiTokenizationAndResponseScan());

        Map<String, String> tags = Map.of("workspace_id", "1");
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Contact bob@acme.io")),
            null, null, null, false, null, null, null, null, tags);

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);
        when(workspaceAiObservabilityTraceService.findByExternalTraceId(any(), anyString()))
            .thenReturn(Optional.empty());

        doAnswer(invocation -> {
            AiObservabilityTrace trace = invocation.getArgument(0);

            ReflectionTestUtils.setField(trace, "id", 300L);

            return null;
        }).when(workspaceAiObservabilityTraceService)
            .createInWorkspace(any(AiObservabilityTrace.class), anyLong());

        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt sentPrompt = invocation.getArgument(0);
            String sentText = sentPrompt.getInstructions()
                .stream()
                .map(Message::getText)
                .collect(Collectors.joining(" "));

            Matcher matcher = PiiToken.pattern()
                .matcher(sentText);

            assertTrue(matcher.find(), "expected the sent prompt to carry a PII token, got: " + sentText);

            return mockChatResponse("Sure, reaching out to " + matcher.group() + " shortly.");
        });

        AiObservabilityTracingHeaders tracingHeaders = new AiObservabilityTracingHeaders(
            "span-test-trace-1", null, "span-test-span", null, "user-1", Map.of(), List.of());

        AiGatewayChatCompletionResponse response = facade.chatCompletion(request, tracingHeaders);

        // Sanity: the CALLER still gets the real value back -- only the persisted span must not.
        assertEquals("Sure, reaching out to bob@acme.io shortly.", response.choices()
            .get(0)
            .message()
            .content());

        ArgumentCaptor<AiObservabilitySpan> spanCaptor = ArgumentCaptor.forClass(AiObservabilitySpan.class);

        verify(aiObservabilitySpanService).create(spanCaptor.capture());

        String spanOutput = spanCaptor.getValue()
            .getOutput();

        assertFalse(spanOutput.contains("bob@acme.io"),
            "span output must not carry the restored real PII value, got: " + spanOutput);
        assertTrue(PiiToken.pattern()
            .matcher(spanOutput)
            .find(), "expected the span output to still carry this exchange's own PII token, got: " + spanOutput);
    }

    /**
     * {@link PiiTokenSession} retains every PII value it minted a token for until closed, so a session that outlives
     * the request it belongs to is a PII store nobody designed (see the class javadoc). This proves the facade closes
     * the session it creates even when the downstream model call throws and {@code chatCompletion}'s inner catch
     * rethrows — the one path where a bare {@code try { ... } finally { session.close(); }} around only the two
     * guardrail calls would miss it.
     */
    @Test
    void testChatCompletionClosesTokenSessionOnDownstreamError() {
        com.bytechef.ee.automation.ai.gateway.guardrail.AiGatewayGuardrails mockGuardrails =
            mock(com.bytechef.ee.automation.ai.gateway.guardrail.AiGatewayGuardrails.class);
        PiiTokenSession session = PiiTokenSession.create();

        when(mockGuardrails.newTokenSession()).thenReturn(session);
        when(mockGuardrails.apply(any(), any(), any(), any(), eq(session))).thenAnswer(invocation -> {
            // Simulate the request actually having minted a token, so a session left unclosed is observable via a
            // non-zero size afterward.
            session.tokenFor("EMAIL", "bob@acme.io");

            return invocation.getArgument(0);
        });

        AiGatewayFacade facade = buildFacade(mockGuardrails);

        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("API call failed"));

        // Asserting the message (not just "some RuntimeException") pins that the call actually reached
        // chatModel.call() through the mocked, session-carrying apply() -- an unrelated failure earlier in the
        // pipeline (e.g. a null request from an unstubbed 3-arg apply() overload) would also satisfy a bare
        // assertThrows(RuntimeException.class) and let this test pass without ever exercising session closing.
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> facade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        assertEquals("API call failed", thrown.getMessage());
        assertEquals(0, session.size());
    }

    @Test
    void testChatCompletionDirectErrorCreatesErrorLog() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("API call failed"));

        assertThrows(RuntimeException.class, () -> aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        verify(aiGatewayRequestLogService).create(any(), any());
    }

    @Test
    void testChatCompletionReturnsCachedResponseOnCacheHit() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(true);
        when(aiGatewayResponseCache.computeCacheKey(any(), any())).thenReturn("test-cache-key");

        AiGatewayChatCompletionResponse cachedResponse = new AiGatewayChatCompletionResponse(
            "cached-id", "chat.completion", 1000L, "openai/gpt-4",
            List.of(new AiGatewayChatCompletionResponse.Choice(
                0, new AiGatewayChatMessage("assistant", "Cached response"), "stop")),
            null);

        when(aiGatewayResponseCache.get("test-cache-key")).thenReturn(cachedResponse);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertEquals("Cached response", response.choices()
            .get(0)
            .message()
            .content());

        verify(aiGatewayChatModelFactory, never()).getChatModel(any());
    }

    @Test
    void testChatCompletionCachesMissedResponseOnCacheMiss() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(true);
        when(aiGatewayResponseCache.computeCacheKey(any(), any())).thenReturn("test-cache-key");
        when(aiGatewayResponseCache.get("test-cache-key")).thenReturn(null);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);

        verify(aiGatewayResponseCache).put(anyString(), any(AiGatewayChatCompletionResponse.class));
    }

    /**
     * A tokenized request must never reach the response cache, on either the read or the write side. Keying on the
     * tokenized request (each session mints a fresh random {@code sessionId}, so the same PII value hashes to a
     * different key every request) means the cache could never hit for PII-bearing prompts anyway, but every miss would
     * still call {@code put} and permanently fill a shared cache with single-use entries. Keying on the
     * PRE-tokenization request instead would be worse, not better: before tokenization two different users' different
     * values legitimately shared a cache key and a cached response, because the cached response never held a real
     * value; now that responses are restored with real values before being returned, sharing a cache entry would let
     * one user's session restore and receive a completely different user's real PII value that a different request
     * happened to trigger. See {@code AiGatewayFacadeImpl#isCacheable}'s javadoc for the full reasoning; this test pins
     * the outward behavior it exists to guarantee.
     */
    @Test
    void testChatCompletionSkipsCacheWhenRequestContainsPiiToken() {
        AiGatewayFacade facade = buildFacade(guardrailsWithPiiTokenizationAndResponseScan());

        Map<String, String> tags = Map.of("workspace_id", "1");
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Contact bob@acme.io")),
            null, null, null, false, null, null, null, null, tags);

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        // shouldCache says yes on its own -- the point of this test is that isCacheable overrules it once the
        // request carries a PII token, not that shouldCache itself changed.
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(true);
        // lenient() -- computeCacheKey is legitimately never reached when the PII-token check correctly short-
        // circuits isCacheable before either cache read/write site calls it. Stubbing a concrete (non-null) key here
        // still matters for catching a REGRESSION: an unstubbed computeCacheKey returns null, and the anyString()
        // verification below would not catch a put(...) call made with that null key, silently letting this test
        // pass even if the PII-token check were removed (see the RED-phase run recorded in the task report, which
        // reproduced exactly that gap before this stub was added).
        org.mockito.Mockito.lenient()
            .when(aiGatewayResponseCache.computeCacheKey(any(), any()))
            .thenReturn("pii-bearing-request-key");

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse("Sure, I'll reach out.");

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = facade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);

        verify(aiGatewayResponseCache, never()).put(anyString(), any(AiGatewayChatCompletionResponse.class));
        verify(aiGatewayResponseCache, never()).get(anyString());
    }

    @Test
    void testChatCompletionErrorLoggingResilienceStillThrowsOriginalException() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("API call failed"));
        doThrow(new RuntimeException("DB error")).when(aiGatewayRequestLogService)
            .create(any(), any());

        RuntimeException exception = assertThrows(
            RuntimeException.class, () -> aiGatewayFacade.chatCompletion(request,
                new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        assertEquals("API call failed", exception.getMessage());
    }

    @Test
    void testChatCompletionWithUnsupportedContentBlockTypeLogsWarning() {
        Map<String, String> tags = Map.of("workspace_id", "1");
        AiGatewayChatMessage multimodalMessage = new AiGatewayChatMessage(
            AiGatewayChatRole.USER, null,
            List.of(new AiGatewayContentBlock(AiGatewayContentBlockType.TEXT, "fallback text", null, null)),
            null, null);

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(multimodalMessage),
            null, null, null, false, null, null, null, null, tags);

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class)))
            .thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);

        verify(aiGatewayRequestLogService).create(any(), any());
    }

    // --- Issue 17: Streaming tests ---

    @Test
    void testChatCompletionStreamSuccess() {
        AiGatewayChatCompletionRequest request = createStreamingRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse streamChunk = mockStreamChunk("Hello ");
        ChatResponse streamChunkFinal = mockStreamChunk("World");

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(streamChunk, streamChunkFinal));
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(new BigDecimal("0.01"));

        Flux<AiGatewayChatCompletionResponse> responseFlux =
            aiGatewayFacade.chatCompletionStream(request, null);

        StepVerifier.create(responseFlux)
            .expectNextCount(2)
            .verifyComplete();
    }

    @Test
    void testChatCompletionStreamBudgetCheckBeforeStreaming() {
        AiGatewayChatCompletionRequest request = createStreamingRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(
            BudgetCheckResult.rejected(new BigDecimal("110"), new BigDecimal("100"), BigDecimal.valueOf(110)));

        // chatCompletionStream returns Flux.defer(...) — the budget check fires on subscription, not on method call.
        StepVerifier.create(aiGatewayFacade.chatCompletionStream(request, null))
            .expectError(BudgetExceededException.class)
            .verify();

        verify(aiGatewayChatModelFactory, never()).getChatModel(any());
    }

    @Test
    void testChatCompletionStreamModelResolutionErrorCreatesLog() {
        AiGatewayChatCompletionRequest request = createStreamingRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of());

        // Same rationale as above — model resolution runs inside the deferred flux.
        StepVerifier.create(aiGatewayFacade.chatCompletionStream(request, null))
            .expectError(IllegalArgumentException.class)
            .verify();

        verify(aiGatewayRequestLogService).create(any(), any());
    }

    @Test
    void testChatCompletionStreamWithRoutingPolicyStreamsFromRoutedDeployment() {
        Map<String, String> tags = Map.of("workspace_id", "1");

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, true, "my-routing-policy", null, null, null, tags);

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());

        AiGatewayRoutingPolicy routingPolicy = new AiGatewayRoutingPolicy(
            "my-routing-policy", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(routingPolicy, "id", 10L);

        when(aiGatewayRoutingPolicyService.getRoutingPolicyByName("my-routing-policy"))
            .thenReturn(routingPolicy);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);
        AiGatewayModelDeployment deployment = new AiGatewayModelDeployment(10L, 1L);

        ReflectionTestUtils.setField(deployment, "id", 100L);

        when(aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(10L))
            .thenReturn(List.of(deployment));
        when(aiModelService.getModel(1L)).thenReturn(model);
        when(aiGatewayProviderService.getProvider(provider.getId())).thenReturn(provider);
        when(aiGatewayRequestLogService.getAverageLatencyByModel(any(Instant.class)))
            .thenReturn(Map.of());
        when(aiGatewayRouter.route(any(), any(), any())).thenReturn(deployment);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse streamChunk = mockStreamChunk("Hello ");
        ChatResponse streamChunkFinal = mockStreamChunk("World");

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(streamChunk, streamChunkFinal));
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(new BigDecimal("0.01"));

        // Invoke the real per-deployment stream builder the facade hands to executeStreamWithRetry, on the primary
        // deployment — verifying the streaming path wires routing through the failover primitive.
        when(aiGatewayRetryHandler.<ChatResponse>executeStreamWithRetry(any(), any()))
            .thenAnswer(invocation -> {
                List<AiGatewayModelDeployment> deployments = invocation.getArgument(0);
                Function<AiGatewayModelDeployment, Flux<ChatResponse>> action = invocation.getArgument(1);

                return action.apply(deployments.get(0));
            });

        StepVerifier.create(aiGatewayFacade.chatCompletionStream(request, null))
            .expectNextCount(2)
            .verifyComplete();

        verify(aiGatewayRetryHandler).executeStreamWithRetry(any(), any());
        verify(aiGatewayRouter).route(any(), any(), any());
    }

    // --- Issue 18: Embedding tests ---

    @Test
    void testEmbeddingSuccess() {
        AiGatewayEmbeddingRequest request = createEmbeddingRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        when(aiGatewayEmbeddingModelFactory.getEmbeddingModel(any())).thenReturn(embeddingModel);

        EmbeddingResponse embeddingResponse = mockEmbeddingResponse();

        when(embeddingModel.call(any(org.springframework.ai.embedding.EmbeddingRequest.class)))
            .thenReturn(embeddingResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(new BigDecimal("0.001"));

        AiGatewayEmbeddingResponse response = aiGatewayFacade.embedding(request, null);

        assertNotNull(response);
        assertEquals("list", response.object());
        assertEquals("openai/gpt-4", response.model());
        assertFalse(response.data()
            .isEmpty());

        verify(aiGatewayRequestLogService).create(any(), any());
    }

    @Test
    void testEmbeddingBudgetCheckBeforeExecution() {
        AiGatewayEmbeddingRequest request = createEmbeddingRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(
            BudgetCheckResult.rejected(new BigDecimal("110"), new BigDecimal("100"), BigDecimal.valueOf(110)));

        assertThrows(BudgetExceededException.class, () -> aiGatewayFacade.embedding(request, null));

        verify(aiGatewayEmbeddingModelFactory, never()).getEmbeddingModel(any());
    }

    @Test
    void testEmbeddingErrorCreatesErrorLog() {
        AiGatewayEmbeddingRequest request = createEmbeddingRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        when(aiGatewayEmbeddingModelFactory.getEmbeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModel.call(any(org.springframework.ai.embedding.EmbeddingRequest.class)))
            .thenThrow(new RuntimeException("Embedding API failed"));

        assertThrows(RuntimeException.class, () -> aiGatewayFacade.embedding(request, null));

        verify(aiGatewayRequestLogService).create(any(), any());
    }

    // --- Issue 19: Routing tests ---

    @Test
    void testChatCompletionWithRoutingDelegatesToRetryHandler() {
        Map<String, String> tags = Map.of("workspace_id", "1");

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, "my-routing-policy", null, null, null, tags);

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());

        AiGatewayRoutingPolicy routingPolicy = new AiGatewayRoutingPolicy(
            "my-routing-policy", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(routingPolicy, "id", 10L);

        when(aiGatewayRoutingPolicyService.getRoutingPolicyByName("my-routing-policy"))
            .thenReturn(routingPolicy);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);
        AiGatewayModelDeployment deployment = new AiGatewayModelDeployment(10L, 1L);

        ReflectionTestUtils.setField(deployment, "id", 100L);

        when(aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(10L))
            .thenReturn(List.of(deployment));
        when(aiModelService.getModel(1L)).thenReturn(model);
        when(aiGatewayProviderService.getProvider(provider.getId())).thenReturn(provider);
        when(aiGatewayRequestLogService.getAverageLatencyByModel(any(Instant.class)))
            .thenReturn(Map.of());
        when(aiGatewayRouter.route(any(), any(), any())).thenReturn(deployment);
        when(aiGatewayRetryHandler.executeWithRetry(any(), any())).thenAnswer(invocation -> {
            AiGatewayChatCompletionResponse mockResponse = new AiGatewayChatCompletionResponse(
                "test-id", "chat.completion", 1000L, "openai/gpt-4",
                List.of(new AiGatewayChatCompletionResponse.Choice(
                    0, new AiGatewayChatMessage("assistant", "Routed response"), "stop")),
                null);

            return mockResponse;
        });

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);
        assertEquals("Routed response", response.choices()
            .get(0)
            .message()
            .content());

        verify(aiGatewayRouter).route(any(), any(), any());
        verify(aiGatewayRetryHandler).executeWithRetry(any(), any());
    }

    @Test
    void testChatCompletionWithRoutingFallsBackToMostCapableTierWhenScorerThrows() {
        Map<String, String> tags = Map.of("workspace_id", "1");

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, "my-routing-policy", null, null, null, tags);

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());

        AiGatewayRoutingPolicy routingPolicy = new AiGatewayRoutingPolicy(
            "my-routing-policy", AiGatewayRoutingStrategyType.INTELLIGENT_QUALITY);

        ReflectionTestUtils.setField(routingPolicy, "id", 10L);

        when(aiGatewayRoutingPolicyService.getRoutingPolicyByName("my-routing-policy"))
            .thenReturn(routingPolicy);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);
        AiGatewayModelDeployment deployment = new AiGatewayModelDeployment(10L, 1L);

        ReflectionTestUtils.setField(deployment, "id", 100L);

        when(aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(10L))
            .thenReturn(List.of(deployment));
        when(aiModelService.getModel(1L)).thenReturn(model);
        when(aiGatewayProviderService.getProvider(provider.getId())).thenReturn(provider);
        when(aiGatewayRequestLogService.getAverageLatencyByModel(any(Instant.class)))
            .thenReturn(Map.of());

        // Scorer failure must never fail the request — the facade degrades to score 1.0 (most capable tier).
        when(promptComplexityScorer.score(any())).thenThrow(new RuntimeException("scorer boom"));

        when(aiGatewayRouter.route(any(), any(), any())).thenReturn(deployment);
        when(aiGatewayRetryHandler.executeWithRetry(any(), any())).thenAnswer(invocation -> {
            AiGatewayChatCompletionResponse mockResponse = new AiGatewayChatCompletionResponse(
                "test-id", "chat.completion", 1000L, "openai/gpt-4",
                List.of(new AiGatewayChatCompletionResponse.Choice(
                    0, new AiGatewayChatMessage("assistant", "Routed response"), "stop")),
                null);

            return mockResponse;
        });

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);

        ArgumentCaptor<AiGatewayRoutingContext> contextCaptor = ArgumentCaptor.forClass(AiGatewayRoutingContext.class);

        verify(aiGatewayRouter).route(any(), any(), contextCaptor.capture());

        assertEquals(1.0, contextCaptor.getValue()
            .promptComplexityScore(), 0.0001);
    }

    @Test
    void testChatCompletionWithRoutingThrowsWhenNoDeployments() {
        Map<String, String> tags = Map.of("workspace_id", "1");

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, "empty-policy", null, null, null, tags);

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());

        AiGatewayRoutingPolicy routingPolicy = new AiGatewayRoutingPolicy(
            "empty-policy", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(routingPolicy, "id", 10L);

        when(aiGatewayRoutingPolicyService.getRoutingPolicyByName("empty-policy"))
            .thenReturn(routingPolicy);
        when(aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(10L))
            .thenReturn(List.of());

        IllegalStateException exception = assertThrows(
            IllegalStateException.class, () -> aiGatewayFacade.chatCompletion(request,
                new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of())));

        assertTrue(exception.getMessage()
            .contains("No deployments configured"));
    }

    // --- Tracing tests ---

    @Test
    void testChatCompletionWithTracingHeadersCreatesTraceAndSpan() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);
        when(workspaceAiObservabilityTraceService.findByExternalTraceId(any(), anyString()))
            .thenReturn(Optional.empty());

        doAnswer(invocation -> {
            AiObservabilityTrace trace = invocation.getArgument(0);

            ReflectionTestUtils.setField(trace, "id", 100L);

            return null;
        }).when(workspaceAiObservabilityTraceService)
            .createInWorkspace(any(AiObservabilityTrace.class), anyLong());

        AiObservabilityTracingHeaders tracingHeaders = new AiObservabilityTracingHeaders(
            "test-trace-1", null, "test-span", null, "user-1", Map.of(), List.of());

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, tracingHeaders);

        assertNotNull(response);

        verify(workspaceAiObservabilityTraceService).createInWorkspace(any(AiObservabilityTrace.class), anyLong());
        verify(aiObservabilitySpanService).create(any(AiObservabilitySpan.class));
    }

    @Test
    void testChatCompletionPersistsExperimentSourceWhenTracingHeadersOverrideIt() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);
        when(workspaceAiObservabilityTraceService.findByExternalTraceId(any(), anyString()))
            .thenReturn(Optional.empty());

        doAnswer(invocation -> {
            AiObservabilityTrace trace = invocation.getArgument(0);

            ReflectionTestUtils.setField(trace, "id", 200L);

            return null;
        }).when(workspaceAiObservabilityTraceService)
            .createInWorkspace(any(AiObservabilityTrace.class), anyLong());

        AiObservabilityTracingHeaders tracingHeaders = new AiObservabilityTracingHeaders(
            "experiment-trace-1", null, "experiment-replay", null, null, Map.of(), List.of(),
            AiObservabilityTraceSource.EXPERIMENT);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, tracingHeaders);

        assertNotNull(response);

        ArgumentCaptor<AiObservabilityTrace> traceCaptor = ArgumentCaptor.forClass(AiObservabilityTrace.class);

        verify(workspaceAiObservabilityTraceService).createInWorkspace(traceCaptor.capture(), anyLong());

        AiObservabilityTrace persistedTrace = traceCaptor.getValue();

        assertEquals(AiObservabilityTraceSource.EXPERIMENT, persistedTrace.getSource());
    }

    @Test
    void testChatCompletionDefaultsToApiSourceWhenTracingHeadersOmitIt() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);
        when(workspaceAiObservabilityTraceService.findByExternalTraceId(any(), anyString()))
            .thenReturn(Optional.empty());

        doAnswer(invocation -> {
            AiObservabilityTrace trace = invocation.getArgument(0);

            ReflectionTestUtils.setField(trace, "id", 201L);

            return null;
        }).when(workspaceAiObservabilityTraceService)
            .createInWorkspace(any(AiObservabilityTrace.class), anyLong());

        // 7-arg constructor leaves source null — facade must default to API
        AiObservabilityTracingHeaders tracingHeaders = new AiObservabilityTracingHeaders(
            "api-trace-1", null, "test-span", null, "user-1", Map.of(), List.of());

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, tracingHeaders);

        assertNotNull(response);

        ArgumentCaptor<AiObservabilityTrace> traceCaptor = ArgumentCaptor.forClass(AiObservabilityTrace.class);

        verify(workspaceAiObservabilityTraceService).createInWorkspace(traceCaptor.capture(), anyLong());

        AiObservabilityTrace persistedTrace = traceCaptor.getValue();

        assertEquals(AiObservabilityTraceSource.API, persistedTrace.getSource());
    }

    @Test
    void testChatCompletionWithSameTraceIdUpdatesExistingTrace() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiObservabilityTrace existingTrace = new AiObservabilityTrace(AiObservabilityTraceSource.API);

        ReflectionTestUtils.setField(existingTrace, "id", 42L);

        existingTrace.setExternalTraceId("test-trace-1");
        existingTrace.setTotalInputTokens(10);
        existingTrace.setTotalOutputTokens(5);
        existingTrace.setTotalLatencyMs(100);
        existingTrace.setTotalCost(BigDecimal.ONE);

        when(workspaceAiObservabilityTraceService.findByExternalTraceId(1L, "test-trace-1"))
            .thenReturn(Optional.of(existingTrace));

        AiObservabilityTracingHeaders tracingHeaders = new AiObservabilityTracingHeaders(
            "test-trace-1", null, "test-span", null, "user-1", Map.of(), List.of());

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, tracingHeaders);

        assertNotNull(response);

        verify(workspaceAiObservabilityTraceService, never()).createInWorkspace(any(AiObservabilityTrace.class),
            anyLong());
        verify(aiObservabilityTraceService).update(any(AiObservabilityTrace.class));
        verify(aiObservabilitySpanService).create(any(AiObservabilitySpan.class));
    }

    /**
     * The facade no longer resolves an external id to a connected user id itself — {@code resolveConnectedUserId} and
     * the {@code ConnectedUserResolver} SPI it called are gone, retired along with the header-based approach. The
     * facade now takes an already-resolved {@code connectedUserId} directly (see
     * {@link #testEmbeddedDefaultPolicyIsResolvedForAKnownConnectedUser} and
     * {@link #testNullConnectedUserIdSkipsEmbeddedResolutionEntirely} below), and
     * {@code resolveEmbeddedDefaultRoutingPolicyId} does the embedded-settings lookup only. Unknown-id rejection —
     * previously pinned here by a test asserting {@code AiGatewayConnectedUserNotFoundException} — moves to the
     * embedded controller that now owns identity resolution.
     */
    @Test
    void testEmbeddedDefaultPolicyIsResolvedForAKnownConnectedUser() {
        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 42L, null, null)));

        assertThat(((AiGatewayFacadeImpl) aiGatewayFacade).resolveEmbeddedDefaultRoutingPolicyId(
            5L, ENVIRONMENT_ID))
                .isEqualTo(42L);
    }

    @Test
    void testNoEmbeddedSettingsYieldsNoEmbeddedDefault() {
        when(embeddedSettingsService.find(ENVIRONMENT_ID)).thenReturn(Optional.empty());

        assertThat(((AiGatewayFacadeImpl) aiGatewayFacade).resolveEmbeddedDefaultRoutingPolicyId(
            5L, ENVIRONMENT_ID))
                .isNull();
    }

    @Test
    void testNullConnectedUserIdSkipsEmbeddedResolutionEntirely() {
        assertThat(((AiGatewayFacadeImpl) aiGatewayFacade).resolveEmbeddedDefaultRoutingPolicyId(
            null, ENVIRONMENT_ID))
                .isNull();

        verifyNoInteractions(embeddedSettingsService);
    }

    // --- Task 5a: the resolved connected user id threaded through to applyRoutingPolicyPrecedence ---

    /**
     * Proves the 4-argument {@code chatCompletion} overload actually threads {@code connectedUserId} into
     * {@code resolveEmbeddedDefaultRoutingPolicyId} rather than merely accepting and ignoring it. The resolved policy
     * id (42) is deliberately made to fail {@code getRoutingPolicy} (simulating a deleted policy) so the request falls
     * back to the same direct-routing path {@code testChatCompletionDirectSuccess} exercises; asserting only the
     * response would still pass if the facade dropped {@code connectedUserId} on the floor — verifying
     * {@code embeddedSettingsService.find(...)} was actually invoked is what proves the id reached routing precedence.
     */
    @Test
    void testChatCompletionThreadsConnectedUserIdIntoEmbeddedRoutingPrecedence() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 42L, null, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(42L))
            .thenThrow(new IllegalArgumentException("policy not found"));

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(
            request, new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()),
            null, 5L);

        assertNotNull(response);

        // The one call this test is about — resolveEmbeddedSettingsOnce resolves the settings row once per request
        // (fix round 1: it used to be fetched again inside resolveEmbeddedDefaultRoutingPolicyId, doubling the
        // uncached PropertyService read+decrypt on every request that reached this level) and the SAME resolved value
        // feeds both checkConnectedUserBudget's cap check and this routing-precedence step.
        verify(embeddedSettingsService).find(ENVIRONMENT_ID);
    }

    /**
     * Streaming counterpart of {@link #testChatCompletionThreadsConnectedUserIdIntoEmbeddedRoutingPrecedence}: the
     * 5-argument {@code chatCompletionStream} overload threads {@code connectedUserId} into
     * {@code applyRoutingPolicyPrecedence}, so the streaming mapping is not a half-wired dead parameter. The model here
     * carries no default of its own, so the chain reaches the embedded default.
     */
    @Test
    void testChatCompletionStreamThreadsConnectedUserIdIntoEmbeddedRoutingPrecedence() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserStreamingRequest();

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse streamChunk = mockStreamChunk("Hello ");

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(streamChunk));
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(new BigDecimal("0.01"));

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 42L, null, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(42L))
            .thenThrow(new IllegalArgumentException("policy not found"));

        StepVerifier.create(aiGatewayFacade.chatCompletionStream(request, null, null, 5L, null))
            .expectNextCount(1)
            .verifyComplete();

        // See testChatCompletionThreadsConnectedUserIdIntoEmbeddedRoutingPrecedence's comment on the same shape:
        // one shared fetch, not one per consumer.
        verify(embeddedSettingsService).find(ENVIRONMENT_ID);
    }

    /**
     * Streaming resolves a model's {@code defaultRoutingPolicyId}, exactly as the sync path does. It did not until the
     * two paths were unified: streaming ran a narrower chain that reached only the embedded default, so the same model
     * default applied to a sync request and was ignored on the streaming request beside it. This test previously
     * asserted the opposite and was inverted deliberately, not repaired — the behavior it pinned is the behavior that
     * was removed.
     *
     * <p>
     * {@code getRoutingPolicy(99L)} is stubbed to throw so the resolved id falls through to direct dispatch (see
     * {@code applyResolvedRoutingPolicy}) and the stream still completes. That keeps the assertion on the one thing
     * this test is about — that the model default is looked up at all — instead of also requiring the router to be
     * stubbed; {@link #testChatCompletionStreamWithRoutingPolicyStreamsFromRoutedDeployment} already covers a policy
     * that does resolve.
     */
    @Test
    void testChatCompletionStreamResolvesTheModelDefaultRoutingPolicy() {
        AiGatewayChatCompletionRequest request = createStreamingRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        model.setDefaultRoutingPolicyId(99L);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse streamChunk = mockStreamChunk("Hello ");

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(streamChunk));
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(new BigDecimal("0.01"));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(99L))
            .thenThrow(new IllegalArgumentException("policy not found"));

        StepVerifier.create(aiGatewayFacade.chatCompletionStream(request, null, null, null, null))
            .expectNextCount(1)
            .verifyComplete();

        verify(aiGatewayRoutingPolicyService).getRoutingPolicy(99L);
    }

    /**
     * Pins the ORDER of the now-shared chain on the streaming path: a model default outranks the embedded default. A
     * connected user is supplied, so the embedded default is reachable, and the model still carries its own default —
     * the embedded-default resolution step must therefore never run. {@code embeddedSettingsService} is still consulted
     * exactly once, though — by {@code checkConnectedUserBudget} (spec §7's per-connected-user cap check), which runs
     * unconditionally for a resolved connected user ahead of routing, independent of which precedence level wins.
     */
    @Test
    void testChatCompletionStreamPrefersTheModelDefaultOverTheEmbeddedDefault() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserStreamingRequest();

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        model.setDefaultRoutingPolicyId(99L);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse streamChunk = mockStreamChunk("Hello ");

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(streamChunk));
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(new BigDecimal("0.01"));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(99L))
            .thenThrow(new IllegalArgumentException("policy not found"));

        StepVerifier.create(aiGatewayFacade.chatCompletionStream(request, null, null, 5L, null))
            .expectNextCount(1)
            .verifyComplete();

        verify(aiGatewayRoutingPolicyService).getRoutingPolicy(99L);
        verify(embeddedSettingsService).find(ENVIRONMENT_ID);
    }

    // --- Phase 2 Task 2: the connected-user policy, the new highest-precedence level ---

    /**
     * A model default may point at a workspace-scoped policy, which embedded traffic has no workspace to route through.
     * The default is dropped and the request routes direct, exactly as a deleted default does.
     *
     * <p>
     * {@code getRoutingPolicyByName} is never reached: the request's {@code routingPolicy} is left null, so the routed
     * path is not entered at all.
     */
    @Test
    void testModelDefaultBelongingToAWorkspaceFallsBackToDirectRoutingForEmbeddedTraffic() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        model.setDefaultRoutingPolicyId(99L);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayRoutingPolicy otherCustomersPolicy = new AiGatewayRoutingPolicy(
            "other-customers-policy", AiGatewayRoutingStrategyType.SIMPLE);

        otherCustomersPolicy.setWorkspaceId(7L);

        ReflectionTestUtils.setField(otherCustomersPolicy, "id", 99L);

        when(aiGatewayRoutingPolicyService.getRoutingPolicy(99L)).thenReturn(otherCustomersPolicy);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, null, null, 5L);

        assertThat(response).isNotNull();

        verify(aiGatewayRoutingPolicyService, never()).getRoutingPolicyByName(any());
        verify(aiGatewayRouter, never()).route(any(), any(), any());
    }

    /**
     * Pins the connected-user level's place at the very top of the chain: when the connected user has their own enabled
     * policy, neither the model default nor the embedded-default RESOLUTION STEP is ever reached. A chain that
     * evaluated every level and picked the first non-null would pass on response shape alone while doing needless work
     * (and, worse, an extra DB round trip) on every request.
     *
     * <p>
     * {@code embeddedSettingsService} is still consulted exactly once, by {@code checkConnectedUserBudget} (spec §7's
     * per-connected-user cap check, unconditional for a resolved connected user, independent of routing precedence) —
     * so the assertion is a single call, not zero, with the routing-specific {@code getRoutingPolicy(99L)} still the
     * tell that the model-default branch of ROUTING specifically never ran.
     *
     * <p>
     * The resolved policy id is made to fail {@code getRoutingPolicy} (mirrors the embedded-default pattern used
     * elsewhere in this class) so the request falls back to the same direct-dispatch path
     * {@code testChatCompletionDirectSuccess} exercises, keeping the assertion on precedence rather than also requiring
     * the router to be stubbed.
     */
    @Test
    void testConnectedUserPolicyWinsOverTheModelDefaultAndTheEmbeddedDefault() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        // The model carries its own default too — resolving it would also satisfy a naive "first non-null wins"
        // chain, so a getRoutingPolicy(99L) call is the tell that the model-default branch ran at all.
        model.setDefaultRoutingPolicyId(99L);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayRoutingPolicy connectedUserPolicy = new AiGatewayRoutingPolicy(
            "connected-user-policy", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(connectedUserPolicy, "id", 7L);

        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 7L, null)));
        // First call: the enabled check on the assigned plan. Second: the chain's rewrite of the request, made to fail
        // so the request falls back to direct dispatch and the assertion stays on precedence.
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(7L))
            .thenReturn(connectedUserPolicy)
            .thenThrow(new IllegalArgumentException("policy not found"));

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, null, null, 5L);

        assertNotNull(response);

        verify(aiGatewayRoutingPolicyService, never()).getRoutingPolicy(99L);
        verify(embeddedSettingsService).find(anyLong());
    }

    /**
     * A disabled connected-user policy is not routed through — it must fall all the way to the next level, exactly as
     * if no policy were bound at all. The model here carries no default of its own, so that next level is the embedded
     * default; {@code getRoutingPolicy(42L)} is the tell that resolution actually walked past the disabled policy
     * rather than stopping (and erroring, or silently routing through a policy an operator turned off).
     */
    @Test
    void testDisabledConnectedUserPolicyFallsThroughToTheEmbeddedDefault() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayRoutingPolicy connectedUserPolicy = new AiGatewayRoutingPolicy(
            "connected-user-policy", AiGatewayRoutingStrategyType.SIMPLE);

        connectedUserPolicy.setEnabled(false);

        ReflectionTestUtils.setField(connectedUserPolicy, "id", 7L);

        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 7L, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(7L)).thenReturn(connectedUserPolicy);

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 42L, null, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(42L))
            .thenThrow(new IllegalArgumentException("policy not found"));

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, null, null, 5L);

        assertNotNull(response);

        verify(aiGatewayRoutingPolicyService).getRoutingPolicy(42L);
    }

    /**
     * Automation traffic reaches the facade with a null {@code connectedUserId} (via the pre-existing 2-argument
     * overload) and must behave exactly as it did before this level was added: no connected-user lookup is even
     * attempted, so this new level is silently a no-op rather than a new dependency automation traffic must satisfy.
     */
    @Test
    void testNullConnectedUserIdSkipsConnectedUserPolicyResolutionEntirely() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(
            request, new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);

        verifyNoInteractions(aiGatewayConnectedUserSettingsService);
        verifyNoInteractions(embeddedSettingsService);
    }

    // --- Task 5b: spend and observability attribution for the resolved connected user ---

    /**
     * Proves the spend-rollup-key half of spec §9: when the caller supplies a resolved connected user id, the
     * {@link AiLlmUsage} row the facade persists (the raw table the rollup and per-customer spend queries read from)
     * carries that id in its existing, otherwise-unused-by-the-gateway {@code userId} column — no schema change. The
     * row claims no workspace: an embedded request has none, so no workspace-scoped trace or span is written for it.
     *
     * <p>
     * The embedded-default policy resolution is made to fail {@code getRoutingPolicy} (mirrors
     * {@code testChatCompletionThreadsConnectedUserIdIntoEmbeddedRoutingPrecedence}) so the request still falls back to
     * the same direct-dispatch path {@code testChatCompletionDirectSuccess} exercises — attribution must not depend on
     * the embedded default actually resolving to a usable policy, only on a connected user id being present.
     */
    @Test
    void testChatCompletionAttributesSpendToResolvedConnectedUser() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 42L, null, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(42L))
            .thenThrow(new IllegalArgumentException("policy not found"));

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(
            request, new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()),
            null, 5L);

        assertNotNull(response);

        ArgumentCaptor<AiLlmUsage> requestLogCaptor = ArgumentCaptor.forClass(AiLlmUsage.class);

        verify(aiGatewayRequestLogService).createForConnectedUser(requestLogCaptor.capture(), anyLong());

        assertEquals(5L, requestLogCaptor.getValue()
            .getUserId());

    }

    /**
     * Property 1 (fix round 1) — the most important test in this round, because it is the case the bug actually hit: a
     * request that already carries an explicit {@code routingPolicy()} makes {@code applyRoutingPolicyPrecedence}
     * return immediately, before it ever reaches the embedded-default step. Before this fix, connected-user resolution
     * lived inside that embedded-default step, so this exact request shape got zero attribution despite carrying a
     * resolved connected user id — a vendor that always sets a routing policy per request would see spend/traces
     * attributed to nobody. Attribution consumes {@code connectedUserId} directly, so it must land regardless of what
     * routing precedence decides. {@code embeddedSettingsService} is still consulted exactly once, by
     * {@code checkConnectedUserBudget} (spec §7's per-connected-user cap check, unconditional for a resolved connected
     * user) — the single-call assertion below proves attribution did NOT ALSO depend on the (never-reached)
     * embedded-default branch, which would have made it a second call.
     */
    @Test
    void testChatCompletionAttributesSpendWhenRequestCarriesExplicitRoutingPolicy() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, "my-routing-policy", null, null, null, null);

        AiGatewayRoutingPolicy routingPolicy = new AiGatewayRoutingPolicy(
            "my-routing-policy", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(routingPolicy, "id", 10L);

        when(aiGatewayRoutingPolicyService.getRoutingPolicyByName("my-routing-policy"))
            .thenReturn(routingPolicy);

        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 10L, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(10L)).thenReturn(routingPolicy);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);
        AiGatewayModelDeployment deployment = new AiGatewayModelDeployment(10L, 1L);

        ReflectionTestUtils.setField(deployment, "id", 100L);

        when(aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(10L))
            .thenReturn(List.of(deployment));
        when(aiModelService.getModel(1L)).thenReturn(model);
        when(aiGatewayProviderService.getProvider(provider.getId())).thenReturn(provider);
        when(aiGatewayRouter.route(any(), any(), any())).thenReturn(deployment);

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        // Unlike testChatCompletionWithRoutingDelegatesToRetryHandler, this test needs the real lambda to run (not a
        // stubbed pass-through response) because request-log creation — the thing being asserted on — happens inside
        // it.
        when(aiGatewayRetryHandler.executeWithRetry(any(), any())).thenAnswer(invocation -> {
            List<AiGatewayModelDeployment> deployments = invocation.getArgument(0);
            Function<AiGatewayModelDeployment, AiGatewayChatCompletionResponse> action = invocation.getArgument(1);

            return action.apply(deployments.get(0));
        });

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(
            request, new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()),
            null, 5L);

        assertNotNull(response);

        ArgumentCaptor<AiLlmUsage> requestLogCaptor = ArgumentCaptor.forClass(AiLlmUsage.class);

        verify(aiGatewayRequestLogService).createForConnectedUser(requestLogCaptor.capture(), anyLong());

        assertEquals(5L, requestLogCaptor.getValue()
            .getUserId());

        verify(embeddedSettingsService).find(ENVIRONMENT_ID);
    }

    /**
     * BYOK (phase 2 task 6), routed path: proves the connected user's own provider — not the one the routing policy's
     * model deployment configured by id — actually serves the request, end to end through {@code chatCompletion}. This
     * is the test the reviewer asked for after finding {@code AiGatewayProviderResolver} referenced nowhere in the
     * automation tree: {@link #testChatCompletionAttributesSpendWhenRequestCarriesExplicitRoutingPolicy} is the same
     * routed-path shape but with no BYOK provider configured, so contrasting the two proves the override, not just that
     * the plumbing compiles.
     */
    @Test
    void testChatCompletionUsesConnectedUsersOwnProviderOverTheTenantProviderOnTheRoutedPath() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, "my-routing-policy", null, null, null, null);

        AiGatewayRoutingPolicy routingPolicy = new AiGatewayRoutingPolicy(
            "my-routing-policy", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(routingPolicy, "id", 10L);

        when(aiGatewayRoutingPolicyService.getRoutingPolicyByName("my-routing-policy"))
            .thenReturn(routingPolicy);

        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 10L, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(10L)).thenReturn(routingPolicy);

        AiGatewayProvider tenantProvider = createProvider();
        AiModel model = createModel(tenantProvider);
        AiGatewayModelDeployment deployment = new AiGatewayModelDeployment(10L, 1L);

        ReflectionTestUtils.setField(deployment, "id", 100L);

        AiGatewayProvider connectedUserProvider = new AiGatewayProvider(
            "Connected user's OpenAI", AiGatewayProviderType.OPENAI, "byok-api-key");

        connectedUserProvider.setConnectedUserId(5L);
        ReflectionTestUtils.setField(connectedUserProvider, "id", 2L);

        when(aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(10L))
            .thenReturn(List.of(deployment));
        when(aiModelService.getModel(1L)).thenReturn(model);
        when(aiGatewayProviderService.getProvider(tenantProvider.getId())).thenReturn(tenantProvider);
        when(aiGatewayProviderService.fetchProviderByConnectedUserIdAndType(5L, AiGatewayProviderType.OPENAI))
            .thenReturn(Optional.of(connectedUserProvider));
        when(aiGatewayRouter.route(any(), any(), any())).thenReturn(deployment);

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        when(aiGatewayRetryHandler.executeWithRetry(any(), any())).thenAnswer(invocation -> {
            List<AiGatewayModelDeployment> deployments = invocation.getArgument(0);
            Function<AiGatewayModelDeployment, AiGatewayChatCompletionResponse> action = invocation.getArgument(1);

            return action.apply(deployments.get(0));
        });

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(
            request, new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()),
            null, 5L);

        assertNotNull(response);

        verify(aiGatewayChatModelFactory)
            .getChatModel(argThat(provider -> provider.getId()
                .equals(connectedUserProvider.getId())));

        ArgumentCaptor<AiLlmUsage> requestLogCaptor = ArgumentCaptor.forClass(AiLlmUsage.class);

        verify(aiGatewayRequestLogService).createForConnectedUser(requestLogCaptor.capture(), anyLong());

        assertEquals(
            AiGatewayProviderType.OPENAI.name(), requestLogCaptor.getValue()
                .getRoutedProvider());
    }

    /**
     * BYOK (phase 2 task 6), fix round 2: the cross-tenant exclusion guard on {@code applyByokOverride}'s fallback
     * branch. The routing policy's model deployment resolves to a provider scoped to connected user 99 (not the
     * requester, connected user 5, who has no BYOK provider of their own) — this is unreachable through any production
     * write path today, but proves that IF such a row existed, {@code applyByokOverride} would refuse to silently run
     * connected user 5's traffic on connected user 99's credentials rather than falling back to it the way it falls
     * back to a genuine tenant-shared (null-connected-user) row.
     */
    @Test
    void testChatCompletionRejectsATenantProviderScopedToADifferentConnectedUser() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, "my-routing-policy", null, null, null, null);

        AiGatewayRoutingPolicy routingPolicy = new AiGatewayRoutingPolicy(
            "my-routing-policy", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(routingPolicy, "id", 10L);

        when(aiGatewayRoutingPolicyService.getRoutingPolicyByName("my-routing-policy"))
            .thenReturn(routingPolicy);

        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 10L, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(10L)).thenReturn(routingPolicy);

        AiGatewayProvider otherConnectedUsersProvider = new AiGatewayProvider(
            "Connected user 99's OpenAI", AiGatewayProviderType.OPENAI, "other-customer-api-key");

        otherConnectedUsersProvider.setConnectedUserId(99L);
        ReflectionTestUtils.setField(otherConnectedUsersProvider, "id", 3L);

        AiModel model = createModel(otherConnectedUsersProvider);
        AiGatewayModelDeployment deployment = new AiGatewayModelDeployment(10L, 1L);

        ReflectionTestUtils.setField(deployment, "id", 100L);

        when(aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(10L))
            .thenReturn(List.of(deployment));
        when(aiModelService.getModel(1L)).thenReturn(model);
        when(aiGatewayProviderService.getProvider(otherConnectedUsersProvider.getId()))
            .thenReturn(otherConnectedUsersProvider);
        when(aiGatewayProviderService.fetchProviderByConnectedUserIdAndType(5L, AiGatewayProviderType.OPENAI))
            .thenReturn(Optional.empty());
        when(aiGatewayRouter.route(any(), any(), any())).thenReturn(deployment);

        when(aiGatewayRetryHandler.executeWithRetry(any(), any())).thenAnswer(invocation -> {
            List<AiGatewayModelDeployment> deployments = invocation.getArgument(0);
            Function<AiGatewayModelDeployment, AiGatewayChatCompletionResponse> action = invocation.getArgument(1);

            return action.apply(deployments.get(0));
        });

        assertThrows(AiGatewayProviderScopeViolationException.class,
            () -> aiGatewayFacade.chatCompletion(
                request, new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()),
                null, 5L));

        verifyNoInteractions(aiGatewayChatModelFactory);
    }

    /**
     * An embedded caller may name only its own assigned plan. The endpoint accepts end-user JWTs, so letting a caller
     * name any plan would be a self-service upgrade. No plan lookup by name happens at all.
     */
    @Test
    void testEmbeddedRequestNamingAPlanItIsNotAssignedIsRejected() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, "premium-plan", null, null, null, null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> aiGatewayFacade.chatCompletion(request, null, null, 5L));

        assertEquals("Routing policy not found: premium-plan", exception.getMessage());

        verify(aiGatewayRoutingPolicyService, never()).getRoutingPolicyByName(any());
        verifyNoInteractions(aiGatewayModelDeploymentService, aiGatewayChatModelFactory);
    }

    /**
     * Streaming counterpart of {@link #testEmbeddedRequestNamingAPlanItIsNotAssignedIsRejected}: the plan restriction
     * runs inside the same {@code applyRoutingPolicyPrecedence} call on both paths, but streaming defers that call
     * until subscription ({@code Flux.defer}), so the refusal must surface as an error signal on the returned
     * {@code Flux}, not as a synchronous throw out of {@code chatCompletionStream} itself.
     */
    @Test
    void testChatCompletionStreamRequestNamingAPlanItIsNotAssignedIsRejected() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, true, "premium-plan", null, null, null, null);

        StepVerifier.create(aiGatewayFacade.chatCompletionStream(request, null, null, 5L, null))
            .expectErrorMatches(throwable -> throwable instanceof IllegalArgumentException
                && "Routing policy not found: premium-plan".equals(throwable.getMessage()))
            .verify();

        verify(aiGatewayRoutingPolicyService, never()).getRoutingPolicyByName(any());
        verifyNoInteractions(aiGatewayModelDeploymentService, aiGatewayChatModelFactory);
    }

    /**
     * The mirror of the previous test: automation traffic (a null {@code connectedUserId}) naming a routing policy in
     * the request is unaffected by the embedded plan restriction and routes through that policy exactly as before.
     */
    @Test
    void testChatCompletionWithRoutingPolicyIsUnaffectedForAutomationTraffic() {
        Map<String, String> tags = Map.of("workspace_id", "1");

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, "my-routing-policy", null, null, null, tags);

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());

        AiGatewayRoutingPolicy routingPolicy = new AiGatewayRoutingPolicy(
            "my-routing-policy", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(routingPolicy, "id", 10L);

        when(aiGatewayRoutingPolicyService.getRoutingPolicyByName("my-routing-policy"))
            .thenReturn(routingPolicy);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);
        AiGatewayModelDeployment deployment = new AiGatewayModelDeployment(10L, 1L);

        ReflectionTestUtils.setField(deployment, "id", 100L);

        when(aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(10L))
            .thenReturn(List.of(deployment));
        when(aiModelService.getModel(1L)).thenReturn(model);
        when(aiGatewayProviderService.getProvider(provider.getId())).thenReturn(provider);
        when(aiGatewayRequestLogService.getAverageLatencyByModel(any(Instant.class)))
            .thenReturn(Map.of());
        when(aiGatewayRouter.route(any(), any(), any())).thenReturn(deployment);
        when(aiGatewayRetryHandler.executeWithRetry(any(), any())).thenAnswer(invocation -> {
            AiGatewayChatCompletionResponse mockResponse = new AiGatewayChatCompletionResponse(
                "test-id", "chat.completion", 1000L, "openai/gpt-4",
                List.of(new AiGatewayChatCompletionResponse.Choice(
                    0, new AiGatewayChatMessage("assistant", "Routed response"), "stop")),
                null);

            return mockResponse;
        });

        // No connectedUserId argument at all — the automation-facing chatCompletion(request, tracingHeaders)
        // overload, which resolves to a null connectedUserId internally.
        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);
        assertEquals("Routed response", response.choices()
            .get(0)
            .message()
            .content());
    }

    /**
     * A plan that does not exist and a plan that exists but is not the caller's are indistinguishable to the caller:
     * same exception type, same message template.
     */
    @Test
    void testUnknownPlanAndAnUnassignedPlanProduceIdenticalErrors() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayRoutingPolicy assignedPlan =
            new AiGatewayRoutingPolicy("assigned-plan", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(assignedPlan, "id", 12L);

        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 12L, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(12L)).thenReturn(assignedPlan);

        IllegalArgumentException missingPlanException = assertThrows(IllegalArgumentException.class,
            () -> aiGatewayFacade.chatCompletion(requestNamingPlan("missing-plan"), null, null, 5L));
        IllegalArgumentException unassignedPlanException = assertThrows(IllegalArgumentException.class,
            () -> aiGatewayFacade.chatCompletion(requestNamingPlan("other-plan"), null, null, 5L));

        assertEquals("Routing policy not found: missing-plan", missingPlanException.getMessage());
        assertEquals("Routing policy not found: other-plan", unassignedPlanException.getMessage());
    }

    /**
     * Defence in depth for {@code AiGatewayRoutingPolicyServiceImpl#updateWorkspaceId}'s refusal to move an assigned
     * plan into a workspace: a plan that somehow still carries a {@code workspaceId} (e.g. it was scoped before ever
     * being assigned) must not route embedded traffic even though its name matches the connected user's assignment.
     */
    @Test
    void testEmbeddedRequestNamingItsAssignedPlanIsRejectedWhenThatPlanIsWorkspaceScoped() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayRoutingPolicy assignedPlan =
            new AiGatewayRoutingPolicy("assigned-plan", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(assignedPlan, "id", 12L);
        assignedPlan.setWorkspaceId(1L);

        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 12L, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(12L)).thenReturn(assignedPlan);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> aiGatewayFacade.chatCompletion(requestNamingPlan("assigned-plan"), null, null, 5L));

        assertEquals("Routing policy not found: assigned-plan", exception.getMessage());

        verifyNoInteractions(aiGatewayModelDeploymentService, aiGatewayChatModelFactory);
    }

    private static AiGatewayChatCompletionRequest requestNamingPlan(String planName) {
        return new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, planName, null, null, null, null);
    }

    /**
     * BYOK (phase 2 task 6), direct path: the "provider/model" identifier resolves the tenant provider purely by type
     * (no model deployment, no provider id to preserve), so this exercises {@code AiGatewayFacadeImpl#resolveModel}'s
     * call into {@code AiGatewayProviderResolver#resolve} directly, rather than the by-id override
     * {@code applyByokOverride} uses on the routed path above.
     */
    @Test
    void testChatCompletionUsesConnectedUsersOwnProviderOverTheTenantProviderOnTheDirectPath() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider tenantProvider = createProvider();
        AiModel model = createModel(tenantProvider);

        AiGatewayProvider connectedUserProvider = new AiGatewayProvider(
            "Connected user's OpenAI", AiGatewayProviderType.OPENAI, "byok-api-key");

        connectedUserProvider.setConnectedUserId(5L);
        ReflectionTestUtils.setField(connectedUserProvider, "id", 2L);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(tenantProvider));
        when(aiModelService.getModel(tenantProvider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayProviderService.fetchProviderByConnectedUserIdAndType(5L, AiGatewayProviderType.OPENAI))
            .thenReturn(Optional.of(connectedUserProvider));

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(
            request, new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()),
            null, 5L);

        assertNotNull(response);

        verify(aiGatewayChatModelFactory)
            .getChatModel(argThat(provider -> provider.getId()
                .equals(connectedUserProvider.getId())));
    }

    /**
     * Property 2 (fix round 1) — the second broken case: the model itself carries a {@code defaultRoutingPolicyId}, so
     * {@code resolveModelDefaultRoutingPolicyId} resolves non-null and {@code applyRoutingPolicyPrecedence} never
     * reaches the embedded-default step either. Same fix, same proof shape as property 1's test, but through the
     * model-default branch of the precedence chain instead of an explicit request-level policy. The model-default
     * policy is made to fail {@code getRoutingPolicy} (mirrors the embedded-default pattern used elsewhere in this
     * class) so the request still falls back to direct dispatch.
     */
    @Test
    void testChatCompletionAttributesSpendWhenModelHasItsOwnDefaultRoutingPolicy() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        model.setDefaultRoutingPolicyId(99L);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(99L))
            .thenThrow(new IllegalArgumentException("policy not found"));

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(
            request, new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()),
            null, 5L);

        assertNotNull(response);

        ArgumentCaptor<AiLlmUsage> requestLogCaptor = ArgumentCaptor.forClass(AiLlmUsage.class);

        verify(aiGatewayRequestLogService).createForConnectedUser(requestLogCaptor.capture(), anyLong());

        assertEquals(5L, requestLogCaptor.getValue()
            .getUserId());

        // See testChatCompletionAttributesSpendWhenRequestCarriesExplicitRoutingPolicy's comment on the same shape:
        // checkConnectedUserBudget consults embeddedSettingsService once, independent of the model-default branch.
        verify(embeddedSettingsService).find(ENVIRONMENT_ID);
    }

    /**
     * The regression pin: a request with no connected user id must attribute exactly as it did before this task —
     * {@code null} connected user, no embedded-settings lookup, and the persisted {@link AiLlmUsage} row's
     * {@code userId} column stays {@code null} exactly as every automation row today. Reuses
     * {@code testChatCompletionDirectSuccess}'s exact setup (the pre-existing 3-argument {@code chatCompletion}
     * overload) so this is a byte-for-byte "nothing changed" check, not a new code path.
     */
    @Test
    void testChatCompletionWithoutConnectedUserIdLeavesSpendAttributionNull() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        doAnswer(invocation -> {
            AiObservabilityTrace trace = invocation.getArgument(0);

            ReflectionTestUtils.setField(trace, "id", 100L);

            return null;
        }).when(workspaceAiObservabilityTraceService)
            .createInWorkspace(any(AiObservabilityTrace.class), anyLong());

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);

        ArgumentCaptor<AiLlmUsage> requestLogCaptor = ArgumentCaptor.forClass(AiLlmUsage.class);

        verify(aiGatewayRequestLogService).create(requestLogCaptor.capture(), any());

        assertNull(requestLogCaptor.getValue()
            .getUserId());

        ArgumentCaptor<AiObservabilitySpan> spanCaptor = ArgumentCaptor.forClass(AiObservabilitySpan.class);

        verify(aiObservabilitySpanService).create(spanCaptor.capture());

        assertNull(spanCaptor.getValue()
            .getMetadata());

        verifyNoInteractions(embeddedSettingsService);
    }

    /**
     * Streaming counterpart of {@link #testChatCompletionAttributesSpendToResolvedConnectedUser}: the request log
     * {@code finalizeStreamRequest} persists carries the resolved connected user id, proving the spend rollup key is
     * threaded through the streaming path's own {@link AiLlmUsage} construction (built directly, not via
     * {@code createSuccessLog}) as well as the sync path's.
     */
    @Test
    void testChatCompletionStreamAttributesSpendToResolvedConnectedUser() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserStreamingRequest();

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse streamChunk = mockStreamChunk("Hello ");

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(streamChunk));
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(new BigDecimal("0.01"));

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 42L, null, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(42L))
            .thenThrow(new IllegalArgumentException("policy not found"));

        StepVerifier.create(aiGatewayFacade.chatCompletionStream(request, null, null, 7L, null))
            .expectNextCount(1)
            .verifyComplete();

        ArgumentCaptor<AiLlmUsage> requestLogCaptor = ArgumentCaptor.forClass(AiLlmUsage.class);

        verify(aiGatewayRequestLogService).createForConnectedUser(requestLogCaptor.capture(), anyLong());

        assertEquals(7L, requestLogCaptor.getValue()
            .getUserId());
    }

    // --- Task 5: per-connected-user budget cap enforcement (spec §7, ⚑5) ---

    /**
     * Pins spec ⚑5: a connected user over their cap is rejected outright with the typed
     * {@link BudgetExceededException}, never silently downgraded to a cheaper model tier. The {@code never()}
     * assertions are the point — a rejected request must not consume a routing decision (the routing policy lookup) or
     * an LLM call (the chat model factory), matching the requirement that the cap is checked BEFORE routing, not after.
     */
    @Test
    void testConnectedUserOverBudgetCapIsRejectedBeforeRoutingOrTheLlmCall() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(
                    ENVIRONMENT_ID, null, null, null, null, null, null, null, new BigDecimal("50.00"))));
        when(aiGatewaySpendService.getTotalCostByConnectedUserId(eq(5L), any(), any()))
            .thenReturn(Money.usd(new BigDecimal("100.00")));

        assertThrows(BudgetExceededException.class,
            () -> aiGatewayFacade.chatCompletion(request, null, null, 5L));

        verify(aiGatewayRoutingPolicyService, never()).getRoutingPolicy(anyLong());
        verify(aiModelService, never()).getModel(anyLong(), anyString());
        verifyNoInteractions(aiGatewayChatModelFactory);
    }

    /**
     * The positive counterpart: a connected user comfortably under their cap is not rejected and the request proceeds
     * to a normal direct-dispatch response, proving the check is a genuine comparison and not a check that always
     * throws once a cap is configured.
     */
    @Test
    void testConnectedUserUnderBudgetCapProceedsNormally() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);
        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(
                    ENVIRONMENT_ID, null, null, null, null, null, null, null, new BigDecimal("50.00"))));
        when(aiGatewaySpendService.getTotalCostByConnectedUserId(eq(5L), any(), any()))
            .thenReturn(Money.usd(new BigDecimal("10.00")));

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, null, null, 5L);

        assertNotNull(response);
    }

    /**
     * A settings row with no cap configured (the {@code null} every other test in this class uses) must never touch
     * {@link AiGatewaySpendService} at all — the cap is opt-in, so a connected user with no cap set pays no query cost
     * for a check that can never fire.
     */
    @Test
    void testNoConnectedUserBudgetCapConfiguredNeverQueriesSpend() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);
        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, null, null, null)));

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, null, null, 5L);

        assertNotNull(response);

        verifyNoInteractions(aiGatewaySpendService);
    }

    /**
     * Spec §3.2: automation behavior must not change. Automation traffic reaches the facade with a null
     * {@code connectedUserId} (the pre-existing 2-argument overload), so the per-connected-user cap — a purely
     * embedded-traffic concern — must never be evaluated: neither the embedded settings row nor the spend query is ever
     * consulted, regardless of what either mock would return if it were.
     */
    @Test
    void testConnectedUserBudgetCapNeverAppliesToAutomationTraffic() {
        AiGatewayChatCompletionRequest request = createDefaultRequest();

        when(aiGatewayBudgetChecker.checkBudget(1L)).thenReturn(BudgetCheckResult.allowed());
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request,
            new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of()));

        assertNotNull(response);

        verifyNoInteractions(embeddedSettingsService);
        verifyNoInteractions(aiGatewaySpendService);
    }

    @Test
    void testConnectedUsersOwnCapOverridesTheEmbeddedDefaultCap() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(
                    ENVIRONMENT_ID, null, null, null, null, null, null, null, new BigDecimal("50.00"))));
        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, null, new BigDecimal("5.00"))));
        when(aiGatewaySpendService.getTotalCostByConnectedUserId(eq(5L), any(), any()))
            .thenReturn(Money.usd(new BigDecimal("10.00")));

        assertThrows(BudgetExceededException.class,
            () -> aiGatewayFacade.chatCompletion(createConnectedUserRequest(), null, null, 5L));

        verifyNoInteractions(aiGatewayChatModelFactory);
    }

    @Test
    void testEmbeddedDefaultCapAppliesWhenTheConnectedUserSetsNoCap() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(
                    ENVIRONMENT_ID, null, null, null, null, null, null, null, new BigDecimal("50.00"))));
        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, null, null)));
        when(aiGatewaySpendService.getTotalCostByConnectedUserId(eq(5L), any(), any()))
            .thenReturn(Money.usd(new BigDecimal("100.00")));

        assertThrows(BudgetExceededException.class,
            () -> aiGatewayFacade.chatCompletion(request, null, null, 5L));

        verify(aiGatewayRoutingPolicyService, never()).getRoutingPolicy(anyLong());
        verifyNoInteractions(aiGatewayChatModelFactory);
    }

    // --- Embedded traffic is scoped by its connected user, never by a workspace ---

    /**
     * An embedded request carries no workspace: its connected user is the caller's whole identity. The workspace
     * budget, the workspace rate limits and the workspace-membership check must therefore never run for it -- each
     * would reject a principal that is not, and can never be, a member of any workspace. The per-connected-user cap is
     * what governs this traffic's spend instead.
     */
    @Test
    void testConnectedUserChatCompletionWithoutWorkspaceTagSkipsTheWorkspaceChecks() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        stubDirectChatCompletion(request);

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(request, null, null, 5L);

        assertNotNull(response);

        verifyNoInteractions(aiGatewayBudgetChecker, aiGatewayRateLimitChecker, permissionService);
    }

    @Test
    void testConnectedUserChatCompletionStreamWithoutWorkspaceTagSkipsTheWorkspaceChecks() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserStreamingRequest();

        stubDirectChatCompletionStream(request);

        StepVerifier.create(aiGatewayFacade.chatCompletionStream(request, null, null, 5L, null))
            .expectNextCount(1)
            .verifyComplete();

        verifyNoInteractions(aiGatewayBudgetChecker, aiGatewayRateLimitChecker, permissionService);
    }

    /**
     * A {@code workspace_id} tag on an embedded request is rejected rather than ignored or honoured. Honouring it would
     * let a vendor's customer name an automation workspace to bill, rate-limit or trace against; ignoring it would
     * silently accept a request whose caller believes it is workspace-scoped.
     */
    @Test
    void testConnectedUserChatCompletionCarryingAWorkspaceTagIsRejected() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createDefaultRequest();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> aiGatewayFacade.chatCompletion(request, null, null, 5L));

        assertEquals(
            "Embedded requests must not carry a 'workspace_id' or 'project_id' tag", exception.getMessage());

        verifyNoInteractions(aiGatewayBudgetChecker, aiGatewayChatModelFactory);
    }

    @Test
    void testConnectedUserChatCompletionCarryingAProjectTagIsRejected() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, null, null, null, null, Map.of("project_id", "3"));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> aiGatewayFacade.chatCompletion(request, null, null, 5L));

        assertEquals(
            "Embedded requests must not carry a 'workspace_id' or 'project_id' tag", exception.getMessage());

        verifyNoInteractions(aiGatewayChatModelFactory);
    }

    /**
     * The embedded settings row -- default routing policy and per-customer spend cap -- is read for the environment the
     * API key authenticated into. The embedded endpoint authenticates with its own API key token, a sibling of the
     * gateway's rather than a subtype, so a lookup that recognised only the gateway token read environment 0
     * (DEVELOPMENT) for every embedded customer in STAGING and PRODUCTION.
     */
    @Test
    void testConnectedUserEmbeddedSettingsAreReadForThePrincipalsOwnEnvironment() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        stubDirectChatCompletion(request);

        aiGatewayFacade.chatCompletion(request, null, null, 5L);

        verify(embeddedSettingsService).find(ENVIRONMENT_ID);
        verify(embeddedSettingsService, never()).find(0L);
    }

    /**
     * A principal carrying no environment cannot say which embedded settings apply, so the request fails closed rather
     * than falling back to DEVELOPMENT's. setUp's admin session token is exactly that shape.
     */
    @Test
    void testConnectedUserWithoutAnAuthenticatedEnvironmentIsRejected() {
        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        assertThrows(AccessDeniedException.class, () -> aiGatewayFacade.chatCompletion(request, null, null, 5L));

        verifyNoInteractions(embeddedSettingsService, aiGatewayChatModelFactory);
    }

    private static void authenticateApiKeyPrincipal(long environmentId) {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestApiKeyAuthenticationToken(environmentId));
    }

    private static AiGatewayConnectedUserSettings connectedUserSettings(
        long connectedUserId, Long routingPolicyId, BigDecimal budgetCap) {

        AiGatewayConnectedUserSettings settings = new AiGatewayConnectedUserSettings(connectedUserId);

        settings.setRoutingPolicyId(routingPolicyId);
        settings.setBudgetCap(budgetCap);

        return settings;
    }

    private AiGatewayChatCompletionRequest createConnectedUserRequest() {
        return new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, null, null, null, null, null);
    }

    private AiGatewayChatCompletionRequest createConnectedUserStreamingRequest() {
        return new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, true, null, null, null, null, null);
    }

    private void stubDirectChatCompletion(AiGatewayChatCompletionRequest request) {
        when(aiGatewayResponseCache.shouldCache(any())).thenReturn(false);

        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse chatResponse = mockChatResponse();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(BigDecimal.ZERO);
    }

    private void stubDirectChatCompletionStream(AiGatewayChatCompletionRequest request) {
        AiGatewayProvider provider = createProvider();
        AiModel model = createModel(provider);

        when(aiGatewayProviderService.getEnabledProviders()).thenReturn(List.of(provider));
        when(aiModelService.getModel(provider.getId(), "gpt-4")).thenReturn(model);
        when(aiGatewayContextCompressor.compress(any(), any(Integer.class))).thenReturn(request.messages());

        ChatModel chatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        ChatResponse streamChunk = mockStreamChunk("Hello ");

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(streamChunk));
        when(aiGatewayCostCalculator.calculateCost(any(), any(Integer.class), any(Integer.class)))
            .thenReturn(new BigDecimal("0.01"));
    }

    private AiGatewayChatCompletionRequest createStreamingRequest() {
        Map<String, String> tags = Map.of("workspace_id", "1");

        return new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, true, null, null, null, null, tags);
    }

    private AiGatewayEmbeddingRequest createEmbeddingRequest() {
        return new AiGatewayEmbeddingRequest(
            "openai/gpt-4", List.of("Hello world"), Map.of("workspace_id", "1"));
    }

    private ChatResponse mockStreamChunk(String content) {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = new AssistantMessage(content);

        when(generation.getOutput()).thenReturn(assistantMessage);
        when(generation.getMetadata()).thenReturn(
            ChatGenerationMetadata.builder()
                .finishReason(null)
                .build());
        when(chatResponse.getResult()).thenReturn(generation);
        when(chatResponse.getMetadata()).thenReturn(null);

        return chatResponse;
    }

    private EmbeddingResponse mockEmbeddingResponse() {
        EmbeddingResponse embeddingResponse = mock(EmbeddingResponse.class);
        Embedding embedding = mock(Embedding.class);

        when(embedding.getOutput()).thenReturn(new float[] {
            0.1f, 0.2f, 0.3f
        });
        when(embedding.getIndex()).thenReturn(0);
        when(embeddingResponse.getResults()).thenReturn(List.of(embedding));

        EmbeddingResponseMetadata metadata = mock(EmbeddingResponseMetadata.class);
        Usage usage = mock(Usage.class);

        when(usage.getPromptTokens()).thenReturn(5);
        when(metadata.getUsage()).thenReturn(usage);
        when(embeddingResponse.getMetadata()).thenReturn(metadata);

        return embeddingResponse;
    }

    private AiGatewayChatCompletionRequest createDefaultRequest() {
        Map<String, String> tags = Map.of("workspace_id", "1");

        return new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, null, null, null, null, tags);
    }

    private AiGatewayProvider createProvider() {
        AiGatewayProvider provider = new AiGatewayProvider(
            "OpenAI", AiGatewayProviderType.OPENAI, "test-api-key");

        ReflectionTestUtils.setField(provider, "id", 1L);

        return provider;
    }

    private AiModel createModel(AiGatewayProvider provider) {
        AiModel model = new AiModel(provider.getId(), "gpt-4");

        model.setContextWindow(128000);

        ReflectionTestUtils.setField(model, "id", 1L);

        return model;
    }

    private ChatResponse mockChatResponse() {
        return mockChatResponse("Hello");
    }

    private ChatResponse mockChatResponse(String text) {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = new AssistantMessage(text);

        when(generation.getOutput()).thenReturn(assistantMessage);
        when(generation.getMetadata()).thenReturn(
            ChatGenerationMetadata.builder()
                .finishReason("stop")
                .build());
        when(chatResponse.getResult()).thenReturn(generation);
        when(chatResponse.getMetadata()).thenReturn(mock(ChatResponseMetadata.class));

        return chatResponse;
    }

    /**
     * An API key principal carrying an environment, the shape both the gateway's and the embedded endpoint's own
     * authentication tokens share through their common parent -- deliberately neither of them, so a facade that only
     * recognises one concrete token type fails these tests.
     */
    @SuppressWarnings("serial")
    private static final class TestApiKeyAuthenticationToken extends AbstractApiKeyAuthenticationToken {

        private TestApiKeyAuthenticationToken(long environmentId) {
            super(environmentId, "test-tenant");
        }
    }
}
