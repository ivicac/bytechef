/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.facade;

import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.ee.automation.ai.gateway.budget.AiGatewayBudgetChecker;
import com.bytechef.ee.automation.ai.gateway.evaluation.AiEvalExecutor;
import com.bytechef.ee.automation.ai.gateway.guardrail.AiGatewayGuardrails;
import com.bytechef.ee.automation.ai.gateway.ratelimit.AiGatewayRateLimitChecker;
import com.bytechef.ee.automation.ai.gateway.service.AiGatewayWorkspaceSettingsService;
import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewayProjectService;
import com.bytechef.ee.automation.ai.observability.service.WorkspaceAiObservabilitySessionService;
import com.bytechef.ee.automation.ai.observability.service.WorkspaceAiObservabilityTraceService;
import com.bytechef.ee.automation.ai.prompt.service.WorkspaceAiPromptService;
import com.bytechef.ee.platform.ai.gateway.cache.AiGatewayResponseCache;
import com.bytechef.ee.platform.ai.gateway.compression.AiGatewayContextCompressor;
import com.bytechef.ee.platform.ai.gateway.cost.AiGatewayCostCalculator;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayEmbeddedSettings;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelDeployment;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProject;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderScopeViolationException;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.BudgetExceededException;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionResponse;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayContentBlock;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayEmbeddingRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayEmbeddingResponse;
import com.bytechef.ee.platform.ai.gateway.dto.AiPromptHeaders;
import com.bytechef.ee.platform.ai.gateway.event.AiGatewayBudgetExceededEvent;
import com.bytechef.ee.platform.ai.gateway.event.AiGatewayTraceCompletedEvent;
import com.bytechef.ee.platform.ai.gateway.metrics.AiGatewayMetrics;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayChatModelFactory;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayEmbeddingModelFactory;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayProviderResolver;
import com.bytechef.ee.platform.ai.gateway.reliability.AiGatewayRetryHandler;
import com.bytechef.ee.platform.ai.gateway.routing.AiGatewayRouter;
import com.bytechef.ee.platform.ai.gateway.routing.AiGatewayRoutingContext;
import com.bytechef.ee.platform.ai.gateway.routing.PromptComplexityScorer;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayEmbeddedSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayModelDeploymentService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewaySpendService;
import com.bytechef.ee.platform.ai.gateway.util.AiGatewayConstraintMatchers;
import com.bytechef.ee.platform.ai.guardrails.StreamingResponseRedactor;
import com.bytechef.ee.platform.ai.guardrails.tokenization.PiiToken;
import com.bytechef.ee.platform.ai.guardrails.tokenization.PiiTokenSession;
import com.bytechef.ee.platform.ai.llm.usage.AiLlmUsage;
import com.bytechef.ee.platform.ai.llm.usage.Money;
import com.bytechef.ee.platform.ai.llm.usage.service.AiLlmUsageService;
import com.bytechef.ee.platform.ai.model.catalog.domain.AiModel;
import com.bytechef.ee.platform.ai.model.catalog.service.AiModelService;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilitySession;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilitySpan;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilitySpanStatus;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilitySpanType;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityTrace;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityTraceStatus;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityTraceTag;
import com.bytechef.ee.platform.ai.observability.facade.AiObservabilityTracingHeaders;
import com.bytechef.ee.platform.ai.observability.security.AiObservabilityUrlValidator;
import com.bytechef.ee.platform.ai.observability.service.AiObservabilitySessionService;
import com.bytechef.ee.platform.ai.observability.service.AiObservabilitySpanService;
import com.bytechef.ee.platform.ai.observability.service.AiObservabilityTraceService;
import com.bytechef.ee.platform.ai.prompt.AiPrompt;
import com.bytechef.ee.platform.ai.prompt.AiPromptVersion;
import com.bytechef.ee.platform.ai.prompt.AiPromptVersionService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.UrlResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

/**
 * Central facade for LLM Gateway chat completion and embedding operations.
 *
 * <p>
 * Request lifecycle:
 * <ol>
 * <li>Pre-request budget check (workspace, then — for embedded traffic — the per-connected-user cap, spec §7)</li>
 * <li>Cache lookup (direct path only)</li>
 * <li>Model resolution (provider/name parsing)</li>
 * <li>Context compression (if messages exceed 85% of context window)</li>
 * <li>Prompt building (with multimodal content support)</li>
 * <li>LLM call (direct or via routing/retry)</li>
 * <li>Cost calculation</li>
 * <li>Request logging</li>
 * <li>Post-request budget enforcement</li>
 * </ol>
 *
 * @author Ivica Cardic
 * @version ee
 */
@Component
@Transactional
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
@SuppressFBWarnings({
    "BX_UNBOXING_IMMEDIATELY_REBOXED", "EI", "PREDICTABLE_RANDOM", "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"
})
public class AiGatewayFacadeImpl implements AiGatewayFacade {

    private static final double CONTEXT_WINDOW_USAGE_RATIO = 0.85;
    private static final BigDecimal DEFAULT_FALLBACK_COST_PER_M_TOKENS = new BigDecimal("10.00");
    private static final Logger log = LoggerFactory.getLogger(AiGatewayFacadeImpl.class);
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    // The unique index that legitimately produces DuplicateKeyException on the trace-dedup recovery path inside
    // ensureTrace. Mirrors the constant in AiObservabilityOtlpIngestFacadeImpl so the two facades agree on the
    // index name. Word-boundary matching prevents a future "uq_ai_obs_trace_ext_trace_idv2" prefix collision
    // from being silently bucketed as a known race when it would actually be a real schema violation.
    private static final String TRACE_DEDUP_INDEX = "uq_ai_obs_trace_ext_trace_id";
    private static final Pattern TRACE_DEDUP_INDEX_PATTERN =
        AiGatewayConstraintMatchers.wordBoundaryPattern(TRACE_DEDUP_INDEX);

    private final AiEvalExecutor aiEvalExecutor;
    private final AiGatewayBudgetChecker aiGatewayBudgetChecker;
    private final AiGatewayRateLimitChecker aiGatewayRateLimitChecker;
    private final AiGatewayChatModelFactory aiGatewayChatModelFactory;
    private final AiGatewayContextCompressor aiGatewayContextCompressor;
    private final AiGatewayCostCalculator aiGatewayCostCalculator;
    private final AiGatewayGuardrails aiGatewayGuardrails;
    private final AiGatewayEmbeddingModelFactory aiGatewayEmbeddingModelFactory;
    private final AiGatewayModelDeploymentService aiGatewayModelDeploymentService;
    private final AiModelService aiModelService;
    private final WorkspaceAiGatewayProjectService workspaceAiGatewayProjectService;
    private final AiGatewayProviderService aiGatewayProviderService;
    private final AiGatewayProviderResolver aiGatewayProviderResolver;
    private final AiLlmUsageService aiGatewayRequestLogService;
    private final AiGatewayResponseCache aiGatewayResponseCache;
    private final AiGatewayRetryHandler aiGatewayRetryHandler;
    private final AiGatewayRouter aiGatewayRouter;
    private final AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;
    private final AiGatewaySpendService aiGatewaySpendService;
    private final PromptComplexityScorer promptComplexityScorer;
    private final WorkspaceAiPromptService workspaceAiPromptService;
    private final AiPromptVersionService aiPromptVersionService;
    private final AiObservabilitySessionService aiObservabilitySessionService;
    private final WorkspaceAiObservabilitySessionService workspaceAiObservabilitySessionService;
    private final WorkspaceAiObservabilityTraceService workspaceAiObservabilityTraceService;
    private final com.bytechef.platform.tag.service.TagService tagService;
    private final AiGatewayWorkspaceSettingsService aiGatewayWorkspaceSettingsService;
    private final com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService;
    private final AiObservabilitySpanService aiObservabilitySpanService;
    private final AiObservabilityTraceService aiObservabilityTraceService;
    // Optional so lightweight app variants without actuator (no MeterRegistry bean) still start. Resolved via
    // ObjectProvider rather than @Nullable injection so tests can supply a real provider without wiring Spring.
    private final ObjectProvider<AiGatewayMetrics> aiGatewayMetricsProvider;
    // Optional collaborator for the embedded-default routing policy step (applyRoutingPolicyPrecedence): an
    // automation-only deployment has no embedded module on its classpath, so this resolves to no bean via
    // ObjectProvider#getIfAvailable() rather than failing to start.
    private final ObjectProvider<AiGatewayEmbeddedSettingsService> embeddedSettingsServiceProvider;
    private final ApplicationEventPublisher applicationEventPublisher;
    @Nullable
    private final PermissionService permissionService;
    private final TransactionTemplate transactionTemplate;

    public AiGatewayFacadeImpl(
        AiEvalExecutor aiEvalExecutor,
        AiGatewayBudgetChecker aiGatewayBudgetChecker,
        @Nullable AiGatewayRateLimitChecker aiGatewayRateLimitChecker,
        AiGatewayChatModelFactory aiGatewayChatModelFactory,
        AiGatewayContextCompressor aiGatewayContextCompressor,
        AiGatewayCostCalculator aiGatewayCostCalculator,
        AiGatewayGuardrails aiGatewayGuardrails,
        AiGatewayEmbeddingModelFactory aiGatewayEmbeddingModelFactory,
        AiGatewayModelDeploymentService aiGatewayModelDeploymentService,
        AiModelService aiModelService,
        WorkspaceAiGatewayProjectService workspaceAiGatewayProjectService,
        AiGatewayProviderService aiGatewayProviderService,
        AiGatewayProviderResolver aiGatewayProviderResolver,
        AiLlmUsageService aiGatewayRequestLogService,
        AiGatewayResponseCache aiGatewayResponseCache,
        AiGatewayRetryHandler aiGatewayRetryHandler,
        AiGatewayRouter aiGatewayRouter,
        AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService,
        AiGatewaySpendService aiGatewaySpendService,
        PromptComplexityScorer promptComplexityScorer,
        WorkspaceAiPromptService workspaceAiPromptService,
        AiPromptVersionService aiPromptVersionService,
        AiObservabilitySessionService aiObservabilitySessionService,
        WorkspaceAiObservabilitySessionService workspaceAiObservabilitySessionService,
        WorkspaceAiObservabilityTraceService workspaceAiObservabilityTraceService,
        AiObservabilitySpanService aiObservabilitySpanService,
        AiObservabilityTraceService aiObservabilityTraceService,
        com.bytechef.platform.tag.service.TagService tagService,
        AiGatewayWorkspaceSettingsService aiGatewayWorkspaceSettingsService,
        com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService,
        ObjectProvider<AiGatewayMetrics> aiGatewayMetricsProvider,
        ObjectProvider<AiGatewayEmbeddedSettingsService> embeddedSettingsServiceProvider,
        ApplicationEventPublisher applicationEventPublisher,
        @Nullable PermissionService permissionService,
        PlatformTransactionManager transactionManager) {

        this.aiEvalExecutor = aiEvalExecutor;
        this.aiGatewayBudgetChecker = aiGatewayBudgetChecker;
        this.aiGatewayRateLimitChecker = aiGatewayRateLimitChecker;
        this.aiGatewayChatModelFactory = aiGatewayChatModelFactory;
        this.aiGatewayContextCompressor = aiGatewayContextCompressor;
        this.aiGatewayCostCalculator = aiGatewayCostCalculator;
        this.aiGatewayGuardrails = aiGatewayGuardrails;
        this.aiGatewayEmbeddingModelFactory = aiGatewayEmbeddingModelFactory;
        this.aiGatewayModelDeploymentService = aiGatewayModelDeploymentService;
        this.aiModelService = aiModelService;
        this.workspaceAiGatewayProjectService = workspaceAiGatewayProjectService;
        this.aiGatewayProviderService = aiGatewayProviderService;
        this.aiGatewayProviderResolver = aiGatewayProviderResolver;
        this.aiGatewayRequestLogService = aiGatewayRequestLogService;
        this.aiGatewayResponseCache = aiGatewayResponseCache;
        this.aiGatewayRetryHandler = aiGatewayRetryHandler;
        this.aiGatewayRouter = aiGatewayRouter;
        this.aiGatewayRoutingPolicyService = aiGatewayRoutingPolicyService;
        this.aiGatewaySpendService = aiGatewaySpendService;
        this.promptComplexityScorer = promptComplexityScorer;
        this.workspaceAiPromptService = workspaceAiPromptService;
        this.aiPromptVersionService = aiPromptVersionService;
        this.aiObservabilitySessionService = aiObservabilitySessionService;
        this.workspaceAiObservabilitySessionService = workspaceAiObservabilitySessionService;
        this.workspaceAiObservabilityTraceService = workspaceAiObservabilityTraceService;
        this.aiObservabilitySpanService = aiObservabilitySpanService;
        this.aiObservabilityTraceService = aiObservabilityTraceService;
        this.tagService = tagService;
        this.aiGatewayWorkspaceSettingsService = aiGatewayWorkspaceSettingsService;
        this.aiGuardrailsWorkspaceSettingsService = aiGuardrailsWorkspaceSettingsService;
        this.aiGatewayMetricsProvider = aiGatewayMetricsProvider;
        this.embeddedSettingsServiceProvider = embeddedSettingsServiceProvider;
        this.applicationEventPublisher = applicationEventPublisher;
        this.permissionService = permissionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private void recordRequestLogPersistFailure(String kind, String outcome) {
        AiGatewayMetrics metrics = aiGatewayMetricsProvider.getIfAvailable();

        if (metrics != null) {
            metrics.incrementRequestLogPersistFailure(kind, outcome);
        }
    }

    /**
     * Validates that the currently-authenticated caller is a member of {@code workspaceId}. Any API-key-authenticated
     * request supplies {@code workspace_id} in the request-body {@code tags}; without this check the caller could bill
     * another workspace's budget, consume its rate limits, or write to its observability traces.
     *
     * <p>
     * Fails closed: if {@link PermissionService} is not wired or there is no authenticated SecurityContext, throws
     * {@link AccessDeniedException}. No silent bypass — a misconfigured deployment or an async thread that failed to
     * propagate SecurityContext MUST surface as an authorization error rather than letting the request through. If the
     * caller is a trusted internal component with no security context (e.g. scheduler, background job), it must set a
     * service-account authentication into {@link SecurityContextHolder} before invoking the facade.
     *
     * <p>
     * Returns silently on: {@code workspaceId == null} (legitimately anonymous request), the principal has global
     * {@code ROLE_ADMIN} (tenant admin bypass), or the principal has a workspace role.
     */
    private void validateWorkspaceAccess(@Nullable Long workspaceId) {
        if (workspaceId == null) {
            return;
        }

        if (permissionService == null) {
            throw new AccessDeniedException(
                "AiGatewayFacade cannot enforce workspace access: PermissionService bean is not wired. " +
                    "This is a configuration error — refusing request to workspace " + workspaceId);
        }

        var authentication = SecurityContextHolder.getContext()
            .getAuthentication();

        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new AccessDeniedException(
                "AiGatewayFacade cannot enforce workspace access: no authenticated SecurityContext. " +
                    "Internal callers must set a service-account authentication before invoking. " +
                    "Refusing request to workspace " + workspaceId);
        }

        if (permissionService.isTenantAdmin()) {
            return;
        }

        String role = permissionService.getMyWorkspaceRole(workspaceId);

        if (role == null) {
            throw new AccessDeniedException(
                "Authenticated caller is not a member of workspace " + workspaceId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiGatewayChatCompletionResponse chatCompletion(
        AiGatewayChatCompletionRequest request, @Nullable AiObservabilityTracingHeaders tracingHeaders) {

        return chatCompletion(request, tracingHeaders, null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiGatewayChatCompletionResponse chatCompletion(
        AiGatewayChatCompletionRequest request, @Nullable AiObservabilityTracingHeaders tracingHeaders,
        @Nullable AiPromptHeaders promptHeaders) {

        return chatCompletion(request, tracingHeaders, promptHeaders, null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiGatewayChatCompletionResponse chatCompletion(
        AiGatewayChatCompletionRequest request, @Nullable AiObservabilityTracingHeaders tracingHeaders,
        @Nullable AiPromptHeaders promptHeaders, @Nullable Long connectedUserId) {

        if (tracingHeaders == null) {
            tracingHeaders = new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of());
        }

        // connectedUserId arrives already resolved by the caller (e.g. from authentication at an embedded
        // controller): routing precedence below AND spend/observability attribution both consume this same value,
        // rather than each resolving it independently.
        long environmentId = resolveAuthenticatedEnvironmentId();

        checkBudget(request.tags(), request.model());
        checkRateLimits(request.tags());

        Optional<AiGatewayEmbeddedSettings> embeddedSettings =
            resolveEmbeddedSettingsAndCheckBudget(connectedUserId, environmentId);

        Long workspaceId = resolveWorkspaceIdFromTags(request.tags());
        Long projectId = resolveProjectId(request.tags());

        ResolvedPrompt resolvedPrompt = resolvePrompt(promptHeaders, workspaceId, request);

        if (resolvedPrompt != null) {
            request = prependSystemMessage(request, resolvedPrompt.content());
        }

        // One token session per HTTP exchange: apply() tokenizes PII on the way out, scanResponse()+restoreResponse()
        // scan then restore it on the way back in (see the comment further down for why those two are kept apart
        // rather than calling the combined redactResponse()). The session holds every PII value the detectors found
        // for as long as it lives, so it must be closed on every termination path -- including the rethrow below --
        // hence the try/finally wrapping both guardrail calls rather than a local variable released only after a
        // successful return.
        PiiTokenSession session = aiGatewayGuardrails.newTokenSession();

        try {
            request = aiGatewayGuardrails.apply(request, workspaceId, projectId, session);

            long startTime = System.currentTimeMillis();

            AiGatewayChatCompletionResponse response;
            boolean success = true;

            request = applyRoutingPolicyPrecedence(request, connectedUserId, embeddedSettings);

            try {
                if (request.routingPolicy() != null) {
                    response = chatCompletionWithRouting(request, connectedUserId);
                } else {
                    response = chatCompletionDirect(request, connectedUserId);
                }
            } catch (Exception exception) {
                success = false;

                processTracingHeaders(
                    tracingHeaders, workspaceId, request, null, startTime, false, resolvedPrompt, connectedUserId);

                throw exception;
            }

            // Dual-directional scanning: redact PII/secrets from the completion before it is traced or returned, so
            // internal data does not leak back through the model output. Non-streaming path only — see
            // chatCompletionStream for the streaming path.
            //
            // Scan, trace, THEN restore -- deliberately three steps, not AiGatewayGuardrails#redactResponse's combined
            // scan+restore. Tracing must see the SCANNED response (this exchange's own [PII_*] tokens still in place,
            // any genuinely new PII/secrets the model produced already masked) and never the RESTORED one: restoring
            // first would persist this request's real PII values into the trace row and, worse, the per-generation
            // span row, which (unlike the trace row) has no digest-instead-of-payload option at all -- see
            // processTracingHeaders. Restoring only after tracing keeps the response returned to the caller correct
            // (the real values still come back) while keeping what gets persisted for observability exactly what the
            // provider actually produced.
            AiGatewayChatCompletionResponse scannedResponse =
                aiGatewayGuardrails.scanResponse(response, workspaceId, projectId);

            processTracingHeaders(
                tracingHeaders, workspaceId, request, scannedResponse, startTime, success, resolvedPrompt,
                connectedUserId);

            response = aiGatewayGuardrails.restoreResponse(scannedResponse, session);

            return withGatewayMetadata(response, request, startTime);
        } finally {
            session.close();
        }

    }

    /**
     * Resolves the effective routing policy with precedence: request-specified → connected-user → model default →
     * embedded default. Workspace and project defaults would extend this chain once their resolution paths are wired;
     * for now, the embedded default (see {@link #resolveEmbeddedDefaultRoutingPolicyId}) is the last fallback before
     * direct routing. {@code connectedUserId} arrives already resolved by the caller — {@code null} for automation
     * traffic, or an embedded request whose caller found no connected user.
     *
     * <p>
     * The connected-user level outranks the model default. A model default is an operator statement about a *model*; a
     * connected-user policy is a statement about *whose request this is* — spec §1 makes the connected user the unit of
     * gateway configuration. A customer's own policy being silently overridden by a model-level default would defeat
     * the feature, so it is resolved first and, when it resolves, the model-default and embedded-default levels below
     * are never consulted at all.
     *
     * <p>
     * Both {@code chatCompletion} and {@code chatCompletionStream} run this same chain. Streaming used to run a
     * narrower one that resolved only the embedded default, so a model's {@code defaultRoutingPolicyId} applied to a
     * sync request and was ignored on the streaming request beside it.
     *
     * <p>
     * {@code embeddedSettings} arrives already resolved by the caller (see {@link #resolveEmbeddedSettingsOnce}) — this
     * method never fetches the settings row itself, so it never re-triggers the read+decrypt
     * {@code AiGatewayEmbeddedSettingsServiceImpl#find} performs, on top of the fetch {@code checkConnectedUserBudget}
     * already made against the same row for the same request.
     */
    private AiGatewayChatCompletionRequest applyRoutingPolicyPrecedence(
        AiGatewayChatCompletionRequest request, @Nullable Long connectedUserId,
        Optional<AiGatewayEmbeddedSettings> embeddedSettings) {

        if (request.routingPolicy() != null) {
            return request;
        }

        Long routingPolicyId = resolveConnectedUserRoutingPolicyId(connectedUserId);

        if (routingPolicyId == null) {
            routingPolicyId = resolveModelDefaultRoutingPolicyId(request.model());
        }

        if (routingPolicyId == null) {
            routingPolicyId = resolveEmbeddedDefaultRoutingPolicyId(connectedUserId, embeddedSettings);
        }

        return applyResolvedRoutingPolicy(request, routingPolicyId, connectedUserId);
    }

    /**
     * Resolves the connected user's own routing policy id — the highest-precedence level in
     * {@link #applyRoutingPolicyPrecedence}, above both the model default and the embedded default (see that method's
     * Javadoc for why). Returns {@code null} when {@code connectedUserId} is {@code null} (automation traffic, or an
     * embedded request whose caller found no connected user), when no policy is bound to that connected user, or when
     * the bound policy is disabled — a disabled policy falls through to the next level rather than routing through a
     * policy an operator turned off.
     */
    private Long resolveConnectedUserRoutingPolicyId(@Nullable Long connectedUserId) {
        if (connectedUserId == null) {
            return null;
        }

        Optional<AiGatewayRoutingPolicy> connectedUserPolicy =
            aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(connectedUserId);

        return connectedUserPolicy.filter(AiGatewayRoutingPolicy::isEnabled)
            .map(AiGatewayRoutingPolicy::getId)
            .orElse(null);
    }

    /**
     * Tail of {@link #applyRoutingPolicyPrecedence}. Also the scope gate for policies this class resolved rather than
     * the caller naming them: a model or embedded default may point at a policy bound to some OTHER connected user, and
     * honouring it would be the same escape {@link #requireRoutingPolicyUsableByConnectedUser} exists to prevent. Such
     * a default falls back to direct routing exactly as a deleted one does, rather than throwing — an operator's
     * misconfiguration must not take every other customer's traffic on that model down, and the failing name is one
     * they never supplied. The WARN carries the ids; the caller is told nothing. Automation ({@code connectedUserId ==
     * null}) is deliberately not scope-checked here, keeping its behaviour byte-for-byte unchanged.
     *
     * <p>
     * Rebuilds {@code request} with the resolved policy's name, or returns {@code request} unchanged when
     * {@code routingPolicyId} is {@code null} or no longer resolves to a real policy (e.g. deleted after being set as a
     * default — falls through to direct routing rather than 500).
     */
    private AiGatewayChatCompletionRequest applyResolvedRoutingPolicy(
        AiGatewayChatCompletionRequest request, @Nullable Long routingPolicyId,
        @Nullable Long connectedUserId) {

        if (routingPolicyId == null) {
            return request;
        }

        try {
            AiGatewayRoutingPolicy policy = aiGatewayRoutingPolicyService.getRoutingPolicy(routingPolicyId);

            Long policyConnectedUserId = policy.getConnectedUserId();

            if (connectedUserId != null && policyConnectedUserId != null
                && !policyConnectedUserId.equals(connectedUserId)) {

                log.warn(
                    "Resolved routing_policy_id={} for model '{}' is bound to connected user {}, not {};" +
                        " falling back to direct routing",
                    routingPolicyId, request.model(), policyConnectedUserId, connectedUserId);

                return request;
            }

            return new AiGatewayChatCompletionRequest(
                request.model(), request.messages(), request.temperature(), request.maxTokens(), request.topP(),
                request.stream(), policy.getName(), request.cache(), request.toolChoice(), request.tools(),
                request.tags());
        } catch (IllegalArgumentException missingPolicy) {
            log.warn(
                "Resolved routing_policy_id={} for model '{}' but policy not found; falling back to direct routing",
                routingPolicyId, request.model());

            return request;
        }
    }

    /**
     * Resolves the embedded default routing policy id for the already-resolved {@code connectedUserId} within
     * {@code environmentId}, fetching the embedded settings row itself. A thin, self-fetching convenience wrapper
     * around {@link #resolveEmbeddedDefaultRoutingPolicyId(Long, Optional)} kept for callers (and tests) that have not
     * already resolved the row — production request handling has, by the time routing precedence runs (see
     * {@link #resolveEmbeddedSettingsOnce}), so this overload is not on that hot path.
     */
    Long resolveEmbeddedDefaultRoutingPolicyId(@Nullable Long connectedUserId, long environmentId) {
        return resolveEmbeddedDefaultRoutingPolicyId(
            connectedUserId, resolveEmbeddedSettingsOnce(connectedUserId, environmentId));
    }

    /**
     * Resolves the embedded default routing policy id from an ALREADY-RESOLVED {@code embeddedSettings} — the hot-path
     * overload {@link #applyRoutingPolicyPrecedence} calls, so a request that reaches this level does not trigger a
     * second {@code AiGatewayEmbeddedSettingsServiceImpl#find} beyond the one {@link #resolveEmbeddedSettingsOnce}
     * already made for {@code checkConnectedUserBudget}. Returns {@code null} when {@code connectedUserId} is
     * {@code null} (automation traffic, or an embedded request whose caller found no connected user) or when
     * {@code embeddedSettings} is empty (no bean available, or no row for the environment).
     */
    private Long resolveEmbeddedDefaultRoutingPolicyId(
        @Nullable Long connectedUserId, Optional<AiGatewayEmbeddedSettings> embeddedSettings) {

        if (connectedUserId == null) {
            return null;
        }

        return embeddedSettings.map(AiGatewayEmbeddedSettings::defaultRoutingPolicyId)
            .orElse(null);
    }

    /**
     * Resolves the embedded settings row for {@code connectedUserId}/{@code environmentId} AT MOST ONCE per request —
     * the single fetch point {@code checkConnectedUserBudget} (spec §7's per-connected-user cap check) and
     * {@link #applyRoutingPolicyPrecedence}'s embedded-default step both read from, so the row's uncached
     * {@code AiGatewayEmbeddedSettingsServiceImpl#find} (a {@code PropertyService} read plus a decrypt) is paid once
     * per request rather than twice on every request that reaches both. Returns {@link Optional#empty()} — without
     * touching {@link #embeddedSettingsServiceProvider} at all — when {@code connectedUserId} is {@code null}
     * (automation traffic), matching every other no-op branch in this class for that case; also empty when no
     * {@link AiGatewayEmbeddedSettingsService} bean is available (an automation-only deployment with no embedded module
     * on the classpath) or when no row exists for {@code environmentId}.
     */
    private Optional<AiGatewayEmbeddedSettings> resolveEmbeddedSettingsOnce(
        @Nullable Long connectedUserId, long environmentId) {

        if (connectedUserId == null) {
            return Optional.empty();
        }

        AiGatewayEmbeddedSettingsService embeddedSettingsService = embeddedSettingsServiceProvider.getIfAvailable();

        if (embeddedSettingsService == null) {
            return Optional.empty();
        }

        return embeddedSettingsService.find(environmentId);
    }

    /**
     * Combines {@link #resolveEmbeddedSettingsOnce} and {@link #checkConnectedUserBudget} into the single call each of
     * {@code chatCompletion}/{@code chatCompletionStreamInternal} makes at request entry, so the settings row is
     * resolved and the cap enforced in one step, with the resolved value handed back for
     * {@link #applyRoutingPolicyPrecedence} to reuse without fetching it again.
     */
    private Optional<AiGatewayEmbeddedSettings> resolveEmbeddedSettingsAndCheckBudget(
        @Nullable Long connectedUserId, long environmentId) {

        Optional<AiGatewayEmbeddedSettings> embeddedSettings =
            resolveEmbeddedSettingsOnce(connectedUserId, environmentId);

        checkConnectedUserBudget(connectedUserId, embeddedSettings);

        return embeddedSettings;
    }

    private Long resolveModelDefaultRoutingPolicyId(String modelIdentifier) {
        try {
            // No connected user id: only the resolved model's own default-routing-policy id is read below, never the
            // provider, so a BYOK override here would be resolved and then silently discarded — skip it.
            ModelResolution resolution = resolveModel(modelIdentifier, null);

            return resolution.model() != null ? resolution.model()
                .getDefaultRoutingPolicyId() : null;
        } catch (IllegalArgumentException resolveFailure) {
            // Known, caller-caused: bad model identifier, unknown provider, or disabled provider. Fall through to
            // direct routing; the downstream call will surface a clearer error. Log at WARN with enough context for
            // operators to spot a misconfigured default — a silently-missed policy means billing rates and routing
            // rules the user expected aren't applied.
            log.warn(
                "Could not resolve default routing policy for model '{}' (reason: {}); " +
                    "falling through to direct routing. Verify the provider is enabled and the model id format is " +
                    "'provider/model'.",
                modelIdentifier, resolveFailure.getMessage());

            return null;
        }
        // Intentionally do NOT catch other RuntimeExceptions here — a DB/transport failure during model lookup is
        // infrastructure-level and must bubble up so the request fails fast rather than silently routing direct.
    }

    /**
     * Returns a copy of {@code response} with observability metadata populated so the public REST controller can emit
     * {@code x-gateway-*} headers. When the response is null or already has metadata (e.g. from a deeper populator),
     * returns the original unchanged.
     */
    private AiGatewayChatCompletionResponse withGatewayMetadata(
        AiGatewayChatCompletionResponse response, AiGatewayChatCompletionRequest request, long startTime) {

        if (response == null || response.gatewayMetadata() != null) {
            return response;
        }

        long latencyMs = System.currentTimeMillis() - startTime;
        String providerName = providerFromModel(response.model() != null ? response.model() : request.model());

        AiGatewayChatCompletionResponse.GatewayMetadata metadata = new AiGatewayChatCompletionResponse.GatewayMetadata(
            providerName,
            response.model(),
            latencyMs,
            null,
            request.routingPolicy(),
            response.id(),
            resolveBudgetWarningRemaining(request.tags()));

        return new AiGatewayChatCompletionResponse(
            response.id(), response.object(), response.created(), response.model(),
            response.choices(), response.usage(), metadata);
    }

    /**
     * Re-runs the budget check after a successful response so the soft-limit warning header reflects the post-request
     * state (the pre-request check could have been below threshold while this call's cost just pushed it over). Returns
     * the remaining USD allowance when the workspace is in warning state, {@code null} otherwise. Defensive: never
     * throws — observability metadata isn't worth failing the user request for.
     */
    private BigDecimal resolveBudgetWarningRemaining(Map<String, String> tags) {
        if (tags == null || !tags.containsKey("workspace_id")) {
            return null;
        }

        try {
            long workspaceId = Long.parseLong(tags.get("workspace_id"));

            AiGatewayBudgetChecker.BudgetCheckResult result = aiGatewayBudgetChecker.checkBudget(workspaceId);

            if (result.thresholdWarning() && result.budgetAmount() != null && result.currentSpend() != null) {
                return result.budgetAmount()
                    .subtract(result.currentSpend());
            }
        } catch (RuntimeException probeFailure) {
            log.warn(
                "Budget warning probe failed for workspace tag {} — budget warning header will be omitted for this response",
                tags.get("workspace_id"), probeFailure);
        }

        return null;
    }

    /**
     * Extracts the provider slug from a {@code provider/model} identifier such as {@code "openai/gpt-4"}. Returns
     * {@code null} when the identifier has no provider prefix so the header is simply omitted rather than misleading.
     */
    private static String providerFromModel(String modelIdentifier) {
        if (modelIdentifier == null) {
            return null;
        }

        int slash = modelIdentifier.indexOf('/');

        return slash > 0 ? modelIdentifier.substring(0, slash) : null;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Flux<AiGatewayChatCompletionResponse> chatCompletionStream(
        AiGatewayChatCompletionRequest request, @Nullable AiObservabilityTracingHeaders tracingHeaders) {

        return chatCompletionStream(request, tracingHeaders, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Flux<AiGatewayChatCompletionResponse> chatCompletionStream(
        AiGatewayChatCompletionRequest request, @Nullable AiObservabilityTracingHeaders tracingHeaders,
        @Nullable AiPromptHeaders promptHeaders) {

        return chatCompletionStream(request, tracingHeaders, promptHeaders, null);
    }

    /**
     * Streaming overload that reports the created trace's internal id back to the caller via {@code traceIdHolder}. The
     * holder is populated inside {@code doOnComplete} (after the trace row is persisted and before the downstream
     * Flux's terminal signal propagates), so a caller that appends a final "totals" chunk via
     * {@code concatWith(Flux.defer(...))} can read {@code traceIdHolder.get()} in the defer and include it in the final
     * chunk. Pass {@code null} if the caller does not need the trace id.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Flux<AiGatewayChatCompletionResponse> chatCompletionStream(
        AiGatewayChatCompletionRequest request, @Nullable AiObservabilityTracingHeaders tracingHeaders,
        @Nullable AiPromptHeaders promptHeaders, @Nullable AtomicLong traceIdHolder) {

        return chatCompletionStream(request, tracingHeaders, promptHeaders, null, traceIdHolder);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Flux<AiGatewayChatCompletionResponse> chatCompletionStream(
        AiGatewayChatCompletionRequest request, @Nullable AiObservabilityTracingHeaders tracingHeaders,
        @Nullable AiPromptHeaders promptHeaders, @Nullable Long connectedUserId,
        @Nullable AtomicLong traceIdHolder) {

        return Flux.defer(
            () -> chatCompletionStreamInternal(request, tracingHeaders, promptHeaders, connectedUserId, traceIdHolder));
    }

    private Flux<AiGatewayChatCompletionResponse> chatCompletionStreamInternal(
        AiGatewayChatCompletionRequest request, @Nullable AiObservabilityTracingHeaders tracingHeaders,
        @Nullable AiPromptHeaders promptHeaders, @Nullable Long connectedUserId, @Nullable AtomicLong traceIdHolder) {

        AiObservabilityTracingHeaders effectiveTracingHeaders = tracingHeaders != null
            ? tracingHeaders
            : new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of());

        // connectedUserId arrives already resolved by the caller — see chatCompletion's Javadoc.
        long environmentId = resolveAuthenticatedEnvironmentId();

        checkBudget(request.tags(), request.model());
        checkRateLimits(request.tags());

        Optional<AiGatewayEmbeddedSettings> embeddedSettings =
            resolveEmbeddedSettingsAndCheckBudget(connectedUserId, environmentId);

        Long workspaceId = resolveWorkspaceIdFromTags(request.tags());
        Long projectId = resolveProjectId(request.tags());

        ResolvedPrompt resolvedPrompt = resolvePrompt(promptHeaders, workspaceId, request);

        // Request-direction guardrails apply as on the sync path. Response scanning on the streaming path is opt-in via
        // the response-scan-streaming-enabled operator flag (see StreamingResponseRedactor): a null redactor here keeps
        // the default token-by-token behavior byte-for-byte unchanged; a non-null one masks PII/secrets across chunk
        // boundaries at the cost of a lookahead delay. The full request-specified → connected-user → model default →
        // embedded default chain is resolved afterward, the same chain the sync path runs.
        AiGatewayChatCompletionRequest effectiveRequest = applyRoutingPolicyPrecedence(
            aiGatewayGuardrails.apply(
                resolvedPrompt != null
                    ? prependSystemMessage(request, resolvedPrompt.content())
                    : request,
                workspaceId, projectId),
            connectedUserId, embeddedSettings);

        long startTime = System.currentTimeMillis();

        AiGatewayProject project = resolveProject(effectiveRequest.tags());

        StreamModelResolution streamModelResolution =
            resolveStreamModelResolution(effectiveRequest, workspaceId, startTime, connectedUserId);

        RoutedDeployments routedDeployments = streamModelResolution.routedDeployments();
        ModelResolution modelResolution = streamModelResolution.modelResolution();

        AiGatewayProvider provider = modelResolution.provider();
        AiModel model = modelResolution.model();

        // servedModel/servedProvider default to the routed primary (or the literal model on the non-routing path) and
        // are overwritten by whichever deployment actually streams a token, so the request log records the deployment
        // that served rather than the one first selected.
        AtomicReference<AiModel> servedModel = new AtomicReference<>(model);
        AtomicReference<AiGatewayProvider> servedProvider = new AtomicReference<>(provider);

        AtomicLong streamInputTokens = new AtomicLong(0);
        AtomicLong streamOutputTokens = new AtomicLong(0);
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        StringBuilder streamOutputContent = new StringBuilder();

        // When streaming response scanning is active, deltas are masked through the redactor and the terminal
        // finish_reason is deferred onto the flush chunk so the client never sees "stop" before the masked tail.
        StreamingResponseRedactor responseRedactor =
            aiGatewayGuardrails.newStreamingResponseRedactor(workspaceId, projectId);
        AtomicReference<String> deferredFinishReason = new AtomicReference<>();

        Flux<ChatResponse> chatResponseFlux;

        if (routedDeployments != null) {
            Map<Long, AiModel> routedModelMap = routedDeployments.modelMap();

            chatResponseFlux = aiGatewayRetryHandler.executeStreamWithRetry(
                routedDeployments.orderedDeployments(),
                deployment -> {
                    AiModel deploymentModel = routedModelMap.get(deployment.getModelId());
                    AiGatewayProvider deploymentTenantProvider =
                        aiGatewayProviderService.getProvider(deploymentModel.getProviderId());
                    AiGatewayProvider deploymentProvider =
                        applyByokOverride(deploymentTenantProvider, connectedUserId);
                    ChatModel deploymentChatModel = aiGatewayChatModelFactory.getChatModel(deploymentProvider);
                    Prompt deploymentPrompt = buildPrompt(
                        compressMessages(effectiveRequest, deploymentModel, project), deploymentModel.getName());

                    return deploymentChatModel.stream(deploymentPrompt)
                        .doOnNext(chatResponse -> {
                            servedModel.set(deploymentModel);
                            servedProvider.set(deploymentProvider);
                        });
                });
        } else {
            ChatModel chatModel = aiGatewayChatModelFactory.getChatModel(provider);
            Prompt prompt = buildPrompt(compressMessages(effectiveRequest, model, project), model.getName());

            chatResponseFlux = chatModel.stream(prompt);
        }

        Flux<AiGatewayChatCompletionResponse> chunkFlux = chatResponseFlux
            .map(chatResponse -> toStreamChunkResponse(
                chatResponse, effectiveRequest, streamInputTokens, streamOutputTokens, streamOutputContent,
                responseRedactor, deferredFinishReason));

        if (responseRedactor != null) {
            chunkFlux = chunkFlux.concatWith(Flux.defer(() -> {
                String tail = responseRedactor.flush();
                String finishReason = deferredFinishReason.get();

                // One redaction metric per stream (the redactor masks per-chunk); mirrors the non-streaming path.
                if (responseRedactor.isRedacted()) {
                    aiGatewayGuardrails.recordResponseRedacted();
                }

                if (tail.isEmpty() && finishReason == null) {
                    return Flux.<AiGatewayChatCompletionResponse>empty();
                }

                return Flux.just(streamChunkOf(effectiveRequest.model(), tail, finishReason));
            }));
        }

        return chunkFlux
            .doOnError(streamError::set)
            .doFinally(signalType -> finalizeStreamRequest(
                signalType, streamInputTokens, streamOutputTokens, streamError, streamOutputContent,
                effectiveRequest, effectiveTracingHeaders, workspaceId, servedModel.get(), servedProvider.get(),
                project, startTime, traceIdHolder, connectedUserId));
    }

    /**
     * Maps a single streamed {@link ChatResponse} chunk to a gateway response chunk, accumulating token usage and the
     * concatenated (raw) output content for the post-stream request log. When {@code responseRedactor} is non-null the
     * emitted delta is masked for PII/secrets and the terminal {@code finish_reason} is captured into
     * {@code deferredFinishReason} instead of being emitted here, so it can ride the redactor's flush chunk after any
     * held-back tail. The raw output accumulation is unchanged (the trace path has its own redaction control).
     */
    private AiGatewayChatCompletionResponse toStreamChunkResponse(
        ChatResponse chatResponse, AiGatewayChatCompletionRequest effectiveRequest, AtomicLong streamInputTokens,
        AtomicLong streamOutputTokens, StringBuilder streamOutputContent,
        @Nullable StreamingResponseRedactor responseRedactor, AtomicReference<String> deferredFinishReason) {

        if (chatResponse.getMetadata() != null && chatResponse.getMetadata()
            .getUsage() != null) {

            long promptTokens = chatResponse.getMetadata()
                .getUsage()
                .getPromptTokens();
            long completionTokens = chatResponse.getMetadata()
                .getUsage()
                .getCompletionTokens();

            if (promptTokens > 0) {
                streamInputTokens.set(promptTokens);
            }

            if (completionTokens > 0) {
                streamOutputTokens.set(completionTokens);
            }
        }

        Generation generation = chatResponse.getResult();

        if (generation == null) {
            return new AiGatewayChatCompletionResponse(
                UUID.randomUUID()
                    .toString(),
                "chat.completion.chunk",
                System.currentTimeMillis() / 1000, effectiveRequest.model(), List.of(), null);
        }

        String chunkText = generation.getOutput()
            .getText();

        if (chunkText != null) {
            streamOutputContent.append(chunkText);
        }

        String deltaText = chunkText;
        String finishReason = generation.getMetadata()
            .getFinishReason();

        if (responseRedactor != null) {
            deltaText = responseRedactor.push(chunkText);

            if (finishReason != null) {
                deferredFinishReason.set(finishReason);
            }

            finishReason = null;
        }

        return streamChunkOf(effectiveRequest.model(), deltaText, finishReason);
    }

    /**
     * Builds a single {@code chat.completion.chunk} response carrying an assistant delta with the given text and finish
     * reason.
     */
    private static AiGatewayChatCompletionResponse streamChunkOf(
        String model, @Nullable String deltaText, @Nullable String finishReason) {

        AiGatewayChatMessage delta = new AiGatewayChatMessage(AiGatewayChatRole.ASSISTANT, deltaText);

        AiGatewayChatCompletionResponse.Choice choice =
            new AiGatewayChatCompletionResponse.Choice(0, delta, finishReason);

        return new AiGatewayChatCompletionResponse(
            UUID.randomUUID()
                .toString(),
            "chat.completion.chunk",
            System.currentTimeMillis() / 1000, model, List.of(choice), null);
    }

    private void finalizeStreamRequest(
        reactor.core.publisher.SignalType signalType, AtomicLong streamInputTokens, AtomicLong streamOutputTokens,
        AtomicReference<Throwable> streamError, StringBuilder streamOutputContent,
        AiGatewayChatCompletionRequest effectiveRequest, AiObservabilityTracingHeaders effectiveTracingHeaders,
        Long workspaceId, AiModel model, AiGatewayProvider provider, AiGatewayProject project,
        long startTime, @Nullable AtomicLong traceIdHolder, @Nullable Long connectedUserId) {

        int inputTokens = (int) Math.min(streamInputTokens.get(), Integer.MAX_VALUE);
        int outputTokens = (int) Math.min(streamOutputTokens.get(), Integer.MAX_VALUE);

        AiLlmUsage requestLog = new AiLlmUsage(
            UUID.randomUUID()
                .toString(),
            effectiveRequest.model());

        requestLog.setRoutedModel(model.getName());
        requestLog.setRoutedProvider(provider.getType()
            .name());
        requestLog.setLatencyMs((int) (System.currentTimeMillis() - startTime));
        requestLog.setInputTokens(inputTokens);
        requestLog.setOutputTokens(outputTokens);

        switch (signalType) {
            case ON_COMPLETE -> requestLog.setStatus(200);
            case ON_ERROR -> {
                requestLog.setStatus(500);

                Throwable error = streamError.get();

                requestLog.setErrorMessage(
                    error != null ? error.getMessage() : "Stream completed with error");
            }
            case CANCEL -> {
                requestLog.setStatus(499);
                requestLog.setErrorMessage("Client disconnected");
            }
            default -> requestLog.setStatus(500);
        }

        // On CANCEL, provider-side usage often hasn't been emitted yet, so input/output tokens are partial. Persist
        // cost
        // as null (unknown) rather than $0 so cost dashboards and alert rules exclude the row rather than summing it as
        // zero. Completed and errored streams still record cost — use the model's configured rates as a fallback when
        // the calculator itself fails.
        if (signalType == reactor.core.publisher.SignalType.CANCEL) {
            requestLog.setCost(null);
        } else {
            try {
                BigDecimal cost = aiGatewayCostCalculator.calculateCost(
                    model, inputTokens, outputTokens);

                requestLog.setCost(cost);
            } catch (IllegalStateException illegalStateException) {
                log.error("Failed to calculate cost for streaming model '{}' — " +
                    "using model's configured rates as fallback to prevent budget bypass",
                    effectiveRequest.model(), illegalStateException);

                BigDecimal fallbackCost = calculateFallbackCost(model, inputTokens, outputTokens);

                requestLog.setCost(fallbackCost);
            }
        }

        setProjectIdFromProject(requestLog, project);
        setApiKeyIdFromAuthentication(requestLog);

        // Spend rollup key — see createSuccessLog's comment on the same field.
        requestLog.setUserId(connectedUserId);

        try {
            transactionTemplate.executeWithoutResult(
                status -> aiGatewayRequestLogService.create(requestLog, workspaceId));
        } catch (Exception exception) {
            recordRequestLogPersistFailure("streaming", "success");

            log.error("Failed to persist request log for streaming model '{}'. " +
                "Alert on ai_gateway.request_log.persist_failure{{kind=streaming}}.",
                effectiveRequest.model(), exception);
        }

        boolean streamSuccess = streamError.get() == null;

        String accumulatedOutput = streamOutputContent.toString();

        processStreamingTracingHeaders(
            effectiveTracingHeaders, workspaceId, effectiveRequest, model, provider, inputTokens,
            outputTokens,
            startTime, streamSuccess, accumulatedOutput.isEmpty() ? null : accumulatedOutput, traceIdHolder,
            connectedUserId);

        enforcePostRequestBudget(effectiveRequest.tags());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiGatewayEmbeddingResponse embedding(
        AiGatewayEmbeddingRequest request, @Nullable AiObservabilityTracingHeaders tracingHeaders) {

        if (tracingHeaders == null) {
            tracingHeaders = new AiObservabilityTracingHeaders(null, null, null, null, null, Map.of(), List.of());
        }

        checkBudget(request.tags(), request.model());
        checkRateLimits(request.tags());

        Long workspaceId = resolveWorkspaceIdFromTags(request.tags());
        Long projectId = resolveProjectId(request.tags());

        // Cover the embeddings path with the request-direction guardrails (redact PII/secrets, block terms/injection)
        // before the input leaves ByteChef — previously only chat completions were guardrailed.
        List<String> guardrailedInputs = aiGatewayGuardrails.applyToInputs(request.input(), workspaceId, projectId);

        long startTime = System.currentTimeMillis();

        // No connected user id: AiGatewayFacade#embedding has no connected-user-aware overload today (unlike
        // chatCompletion), so BYOK cannot apply to embeddings yet — an accepted, pre-existing gap, not a regression
        // introduced here.
        ModelResolution modelResolution = resolveModel(request.model(), null);

        AiGatewayProvider provider = modelResolution.provider();
        AiModel model = modelResolution.model();

        EmbeddingModel embeddingModel = aiGatewayEmbeddingModelFactory.getEmbeddingModel(provider);

        EmbeddingRequest embeddingRequest = new EmbeddingRequest(
            guardrailedInputs,
            org.springframework.ai.embedding.EmbeddingOptions.builder()
                .model(model.getName())
                .build());

        try {
            EmbeddingResponse embeddingResponse = embeddingModel.call(embeddingRequest);

            List<AiGatewayEmbeddingResponse.EmbeddingData> embeddingDataList =
                embeddingResponse.getResults()
                    .stream()
                    .map(embedding -> {
                        List<Float> values = new ArrayList<>();

                        for (float value : embedding.getOutput()) {
                            values.add(value);
                        }

                        return new AiGatewayEmbeddingResponse.EmbeddingData(
                            "embedding", embedding.getIndex(), values);
                    })
                    .toList();

            int promptTokens = 0;

            if (embeddingResponse.getMetadata() != null && embeddingResponse.getMetadata()
                .getUsage() != null) {

                promptTokens = (int) Math.min(
                    embeddingResponse.getMetadata()
                        .getUsage()
                        .getPromptTokens(),
                    Integer.MAX_VALUE);
            }

            AiLlmUsage requestLog = new AiLlmUsage(
                UUID.randomUUID()
                    .toString(),
                request.model());

            requestLog.setRoutedModel(model.getName());
            requestLog.setRoutedProvider(provider.getType()
                .name());
            requestLog.setLatencyMs((int) (System.currentTimeMillis() - startTime));
            requestLog.setInputTokens(promptTokens);
            requestLog.setStatus(200);

            BigDecimal cost = aiGatewayCostCalculator.calculateCost(model, promptTokens, 0);

            requestLog.setCost(cost);

            setApiKeyIdFromAuthentication(requestLog);

            try {
                aiGatewayRequestLogService.create(requestLog, workspaceId);
            } catch (Exception logException) {
                recordRequestLogPersistFailure("embedding", "success");

                log.error("Failed to persist request log for embedding model '{}' — " +
                    "cost of ${} will be missing from spend tracking. " +
                    "Alert on ai_gateway.request_log.persist_failure{{kind=embedding}}.",
                    request.model(), requestLog.getCost(), logException);
            }

            enforcePostRequestBudget(request.tags());

            AiGatewayChatCompletionResponse.GatewayMetadata embeddingMetadata =
                new AiGatewayChatCompletionResponse.GatewayMetadata(
                    provider.getType()
                        .name()
                        .toLowerCase(),
                    model.getName(),
                    System.currentTimeMillis() - startTime,
                    null,
                    null,
                    UUID.randomUUID()
                        .toString(),
                    resolveBudgetWarningRemaining(request.tags()));

            AiGatewayEmbeddingResponse embeddingResult = new AiGatewayEmbeddingResponse(
                "list",
                embeddingDataList,
                request.model(),
                new AiGatewayEmbeddingResponse.Usage(promptTokens, promptTokens),
                embeddingMetadata);

            processEmbeddingTracingHeaders(
                tracingHeaders, workspaceId, request, promptTokens, startTime, true);

            return embeddingResult;
        } catch (Exception exception) {
            AiLlmUsage errorLog = new AiLlmUsage(
                UUID.randomUUID()
                    .toString(),
                request.model());

            errorLog.setRoutedModel(model.getName());
            errorLog.setRoutedProvider(provider.getType()
                .name());
            errorLog.setLatencyMs((int) (System.currentTimeMillis() - startTime));
            errorLog.setStatus(resolveErrorStatus(exception));
            errorLog.setErrorMessage(exception.getMessage());

            setApiKeyIdFromAuthentication(errorLog);

            try {
                aiGatewayRequestLogService.create(errorLog, workspaceId);
            } catch (Exception logException) {
                recordRequestLogPersistFailure("embedding", "error");

                log.error("Failed to log embedding error for model '{}'. Original error: {}",
                    request.model(), exception.getMessage(), logException);
            }

            processEmbeddingTracingHeaders(
                tracingHeaders, workspaceId, request, 0, startTime, false);

            throw exception;
        }
    }

    private AiGatewayChatCompletionResponse chatCompletionDirect(
        AiGatewayChatCompletionRequest request, @Nullable Long connectedUserId) {
        if (isCacheable(request)) {
            String cacheKey = aiGatewayResponseCache.computeCacheKey(request, connectedUserId);
            AiGatewayChatCompletionResponse cached = aiGatewayResponseCache.get(cacheKey);

            if (cached != null) {
                return cached;
            }
        }

        Long workspaceId = resolveWorkspaceIdFromTags(request.tags());
        long startTime = System.currentTimeMillis();

        ModelResolution modelResolution = resolveModel(request.model(), connectedUserId);

        AiGatewayProvider provider = modelResolution.provider();
        AiModel model = modelResolution.model();

        AiGatewayProject project = resolveProject(request.tags());

        ChatModel chatModel = aiGatewayChatModelFactory.getChatModel(provider);

        AiGatewayChatCompletionRequest processedRequest = compressMessages(request, model, project);

        Prompt prompt = buildPrompt(processedRequest, model.getName());

        try {
            ChatResponse chatResponse = chatModel.call(prompt);

            int[] tokenCounts = extractTokenCounts(chatResponse);

            AiLlmUsage requestLog = createSuccessLog(
                request, model, provider, startTime, tokenCounts[0], tokenCounts[1], connectedUserId);

            setProjectIdFromProject(requestLog, project);

            try {
                aiGatewayRequestLogService.create(requestLog, workspaceId);
            } catch (Exception logException) {
                recordRequestLogPersistFailure("chat", "success");

                log.error("Failed to persist request log for chat completion model '{}' — " +
                    "cost of ${} will be missing from spend tracking. " +
                    "Alert on ai_gateway.request_log.persist_failure{{kind=chat}}.",
                    request.model(), requestLog.getCost(), logException);
            }

            enforcePostRequestBudget(request.tags());

            AiGatewayChatCompletionResponse response = toResponse(chatResponse, request.model());

            if (isCacheable(request)) {
                String cacheKey = aiGatewayResponseCache.computeCacheKey(request, connectedUserId);

                try {
                    aiGatewayResponseCache.put(cacheKey, response);
                } catch (Exception cacheException) {
                    log.warn(
                        "Failed to cache chat completion response for model '{}' (key={}); " +
                            "request already succeeded, continuing",
                        request.model(), cacheKey, cacheException);
                }
            }

            return response;
        } catch (Exception exception) {
            try {
                AiLlmUsage errorLog = createErrorLog(request, startTime, exception, connectedUserId);

                setProjectIdFromProject(errorLog, project);

                aiGatewayRequestLogService.create(errorLog, workspaceId);
            } catch (Exception logException) {
                recordRequestLogPersistFailure("chat", "error");

                log.error("Failed to log chat completion error for model '{}'. Original error: {}",
                    request.model(), exception.getMessage(), logException);
            }

            throw exception;
        }
    }

    private AiGatewayChatCompletionResponse chatCompletionWithRouting(
        AiGatewayChatCompletionRequest request, @Nullable Long connectedUserId) {

        // Response cache is keyed on the request content (model-agnostic), so it applies to the routing path exactly
        // as it does to the direct path; previously routed requests always bypassed the cache.
        if (isCacheable(request)) {
            String cacheKey = aiGatewayResponseCache.computeCacheKey(request, connectedUserId);
            AiGatewayChatCompletionResponse cached = aiGatewayResponseCache.get(cacheKey);

            if (cached != null) {
                return cached;
            }
        }

        Long workspaceId = resolveWorkspaceIdFromTags(request.tags());
        long startTime = System.currentTimeMillis();

        AiGatewayRoutingPolicy routingPolicy = requireRoutingPolicyUsableByConnectedUser(
            aiGatewayRoutingPolicyService.getRoutingPolicyByName(request.routingPolicy()), connectedUserId,
            request.routingPolicy());

        List<AiGatewayModelDeployment> deployments =
            aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(routingPolicy.getId());

        if (deployments.isEmpty()) {
            throw new IllegalStateException(
                "No deployments configured for routing policy: " + routingPolicy.getName());
        }

        Map<Long, AiModel> modelMap = deployments.stream()
            .map(AiGatewayModelDeployment::getModelId)
            .distinct()
            .collect(Collectors.toMap(
                modelId -> modelId,
                modelId -> aiModelService.getModel(modelId)));

        List<AiGatewayModelDeployment> enabledDeployments = deployments.stream()
            .filter(AiGatewayModelDeployment::isEnabled)
            .toList();

        if (enabledDeployments.isEmpty()) {
            throw new IllegalStateException(
                "No enabled deployments for routing policy: " + routingPolicy.getName());
        }

        AiGatewayProject project = resolveProject(request.tags());

        AiGatewayRoutingContext routingContext = buildRoutingContext(request, modelMap);

        AiGatewayModelDeployment primaryDeployment = aiGatewayRouter.route(
            routingPolicy.getStrategy(), enabledDeployments, routingContext);

        List<AiGatewayModelDeployment> orderedDeployments = new ArrayList<>();

        orderedDeployments.add(primaryDeployment);

        for (AiGatewayModelDeployment deployment : enabledDeployments) {
            if (!deployment.getId()
                .equals(primaryDeployment.getId())) {

                orderedDeployments.add(deployment);
            }
        }

        try {
            AiGatewayChatCompletionResponse response =
                aiGatewayRetryHandler.executeWithRetry(orderedDeployments, deployment -> {
                    AiModel model = modelMap.get(deployment.getModelId());
                    AiGatewayProvider tenantProvider = aiGatewayProviderService.getProvider(model.getProviderId());
                    AiGatewayProvider provider = applyByokOverride(tenantProvider, connectedUserId);

                    ChatModel chatModel = aiGatewayChatModelFactory.getChatModel(provider);

                    AiGatewayChatCompletionRequest processedRequest = compressMessages(request, model, project);

                    Prompt prompt = buildPrompt(processedRequest, model.getName());

                    ChatResponse chatResponse = chatModel.call(prompt);

                    int[] tokenCounts = extractTokenCounts(chatResponse);

                    AiLlmUsage requestLog = createSuccessLog(
                        request, model, provider, startTime, tokenCounts[0], tokenCounts[1], connectedUserId);

                    setProjectIdFromProject(requestLog, project);

                    requestLog.setRoutingPolicyId(routingPolicy.getId());
                    requestLog.setRoutingStrategy(
                        routingPolicy.getStrategy() == null ? null : routingPolicy.getStrategy()
                            .ordinal());

                    try {
                        aiGatewayRequestLogService.create(requestLog, workspaceId);
                    } catch (Exception logException) {
                        recordRequestLogPersistFailure("routing", "success");

                        log.error("Failed to persist request log for routed model '{}' — " +
                            "cost of ${} will be missing from spend tracking. " +
                            "Alert on ai_gateway.request_log.persist_failure{{kind=routing}}.",
                            request.model(), requestLog.getCost(), logException);
                    }

                    enforcePostRequestBudget(request.tags());

                    return toResponse(chatResponse, request.model());
                });

            if (isCacheable(request)) {
                String cacheKey = aiGatewayResponseCache.computeCacheKey(request, connectedUserId);

                try {
                    aiGatewayResponseCache.put(cacheKey, response);
                } catch (Exception cacheException) {
                    log.warn(
                        "Failed to cache routed chat completion response for model '{}' (key={}); " +
                            "request already succeeded, continuing",
                        request.model(), cacheKey, cacheException);
                }
            }

            return response;
        } catch (Exception exception) {
            try {
                AiLlmUsage errorLog = createErrorLog(request, startTime, exception, connectedUserId);

                setProjectIdFromProject(errorLog, project);

                errorLog.setRoutingPolicyId(routingPolicy.getId());
                errorLog.setRoutingStrategy(routingPolicy.getStrategy() == null ? null : routingPolicy.getStrategy()
                    .ordinal());

                aiGatewayRequestLogService.create(errorLog, workspaceId);
            } catch (Exception logException) {
                recordRequestLogPersistFailure("routing", "error");

                log.error("Failed to log routing error for model '{}'. Original error: {}",
                    request.model(), exception.getMessage(), logException);
            }

            throw exception;
        }
    }

    private void checkBudget(Map<String, String> tags, String model) {
        if (tags == null || !tags.containsKey("workspace_id")) {
            throw new IllegalArgumentException(
                "Request for model '" + model + "' is missing required 'workspace_id' tag");
        }

        try {
            long workspaceId = Long.parseLong(tags.get("workspace_id"));

            // Enforce that the authenticated caller actually belongs to the workspace they're trying to charge.
            // Without this check a caller with any valid API key could spoof workspace_id in the request tags to
            // bill another tenant's budget or evade their own.
            validateWorkspaceAccess(workspaceId);

            AiGatewayBudgetChecker.BudgetCheckResult budgetResult =
                aiGatewayBudgetChecker.checkBudget(workspaceId);

            if (!budgetResult.requestAllowed()) {
                applicationEventPublisher.publishEvent(new AiGatewayBudgetExceededEvent(
                    workspaceId, model, budgetResult.currentSpend(), budgetResult.budgetAmount()));

                throw new BudgetExceededException(
                    "Budget limit exceeded for workspace " + workspaceId +
                        ". Current spend: $" + budgetResult.currentSpend() +
                        " / Budget: $" + budgetResult.budgetAmount(),
                    Money.usd(budgetResult.budgetAmount()), Money.usd(budgetResult.currentSpend()));
            }

            if (budgetResult.thresholdWarning()) {
                log.warn(
                    "Budget threshold warning for workspace {}: current spend ${} / budget ${} ({}%)",
                    workspaceId, budgetResult.currentSpend(), budgetResult.budgetAmount(),
                    budgetResult.usagePercentage());
            }
        } catch (NumberFormatException numberFormatException) {
            throw new IllegalArgumentException(
                "Invalid workspace_id tag: " + tags.get("workspace_id"), numberFormatException);
        }
    }

    /**
     * Enforces the embedded settings record's per-connected-user default spend cap (spec §7, ⚑5) — a customer over
     * budget is rejected outright, never silently downgraded to a cheaper tier. Called ahead of
     * {@link #applyRoutingPolicyPrecedence} so a rejected request consumes neither a routing decision nor an LLM call.
     *
     * <p>
     * {@code embeddedSettings} arrives already resolved by the caller (see {@link #resolveEmbeddedSettingsOnce}) — this
     * method never fetches the settings row itself. No-ops — and never touches {@link #aiGatewaySpendService} — for:
     * automation traffic ({@code connectedUserId == null}, spec §3.2: automation behavior must not change), an empty
     * {@code embeddedSettings} (no {@link AiGatewayEmbeddedSettingsService} bean available, or no row for the
     * environment), or a settings row whose cap is unset. The cap is opt-in, matching every other field on
     * {@link AiGatewayEmbeddedSettings}'s "null means inherit / not set" convention.
     *
     * <p>
     * Spend is compared as {@link Money}, not raw {@code BigDecimal} — mirroring
     * {@code AiGatewayBudgetChecker#sumSpend}, which reads this same table for the same purpose and documents why: a
     * summary row in a currency other than USD must fail the comparison (via {@link Money#compareTo}'s currency check)
     * rather than silently produce an arithmetic-but-wrong number. The schema is all-USD today, so this is
     * defense-in-depth, not a currently-reachable branch.
     */
    private void checkConnectedUserBudget(
        @Nullable Long connectedUserId, Optional<AiGatewayEmbeddedSettings> embeddedSettings) {

        if (connectedUserId == null) {
            return;
        }

        if (embeddedSettings.isEmpty()) {
            return;
        }

        BigDecimal cap = embeddedSettings.get()
            .defaultConnectedUserBudgetCap();

        if (cap == null) {
            return;
        }

        Instant periodStart = currentBillingPeriodStart();

        Money currentSpend =
            aiGatewaySpendService.getTotalCostByConnectedUserId(connectedUserId, periodStart, Instant.now());
        Money capMoney = Money.usd(cap);

        if (currentSpend.compareTo(capMoney) >= 0) {
            throw new BudgetExceededException(
                "Budget cap exceeded for connected user " + connectedUserId +
                    ". Current spend: $" + currentSpend.amount() + " / Cap: $" + cap,
                capMoney, currentSpend);
        }
    }

    /**
     * Start of the current calendar month in UTC — the fixed window the per-connected-user cap resets on. Unlike a
     * workspace {@code AiGatewayBudget}, which carries its own configurable period and enforcement mode, the
     * per-connected-user cap is a single settings-record value (spec §7), so a fixed monthly window keeps the feature
     * proportional to what it protects rather than duplicating the workspace budget's period machinery.
     */
    private static Instant currentBillingPeriodStart() {
        return ZonedDateTime.now(ZoneOffset.UTC)
            .withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS)
            .toInstant();
    }

    private void checkRateLimits(Map<String, String> tags) {
        if (aiGatewayRateLimitChecker == null || tags == null || !tags.containsKey("workspace_id")) {
            return;
        }

        try {
            long workspaceId = Long.parseLong(tags.get("workspace_id"));

            // Workspace membership was already validated in checkBudget for chat/embedding paths; re-run for callers
            // that skipped the budget check.
            validateWorkspaceAccess(workspaceId);

            Long projectId = tags.containsKey("project_id") ? Long.parseLong(tags.get("project_id")) : null;
            String userId = tags.get("user_id");

            aiGatewayRateLimitChecker.checkRateLimits(workspaceId, projectId, userId, tags);
        } catch (NumberFormatException numberFormatException) {
            throw new IllegalArgumentException(
                "Invalid numeric tag value during rate limit check " +
                    "(workspace_id/project_id must parse as long)",
                numberFormatException);
        }
    }

    private void enforcePostRequestBudget(Map<String, String> tags) {
        if (tags == null || !tags.containsKey("workspace_id")) {
            log.error(
                "Missing workspace_id tag during post-request budget enforcement — " +
                    "this indicates a bug in the request pipeline");

            return;
        }

        try {
            long workspaceId = Long.parseLong(tags.get("workspace_id"));

            aiGatewayBudgetChecker.recordSpendAndEnforce(workspaceId);
        } catch (RuntimeException exception) {
            // Emit a metric so operators can alert on the silent HARD→SOFT degradation. A DB outage here lets
            // requests continue at full cost for the duration of the outage without any user-visible signal.
            AiGatewayMetrics metrics = aiGatewayMetricsProvider.getIfAvailable();

            if (metrics != null) {
                metrics.incrementPostRequestBudgetFailure();
            }

            log.error(
                "Post-request budget enforcement failed for workspace_id={} — " +
                    "pre-request budget checking remains active via the budget checker cache. " +
                    "Alert on ai_gateway.budget.post_request_failure counter.",
                tags.get("workspace_id"), exception);
        }
    }

    private Prompt buildPrompt(AiGatewayChatCompletionRequest request, String modelName) {
        List<Message> messages = request.messages()
            .stream()
            .map(this::toSpringAiMessage)
            .toList();

        if (request.tools() != null && !request.tools()
            .isEmpty()) {
            OpenAiChatOptions.Builder openAiOptionsBuilder = OpenAiChatOptions.builder()
                .model(modelName);

            if (request.temperature() != null) {
                openAiOptionsBuilder.temperature(request.temperature());
            }

            if (request.maxTokens() != null) {
                openAiOptionsBuilder.maxTokens(request.maxTokens());
            }

            if (request.topP() != null) {
                openAiOptionsBuilder.topP(request.topP());
            }

            List<Map<String, Object>> functionTools = request.tools()
                .stream()
                .map(tool -> Map.<String, Object>of(
                    "type", "function",
                    "function", Map.of(
                        "name", tool.function()
                            .name(),
                        "description", tool.function()
                            .description(),
                        "parameters", tool.function()
                            .parameters())))
                .toList();

            openAiOptionsBuilder.extraBody(Map.of("tools", functionTools));

            return new Prompt(messages, openAiOptionsBuilder.build());
        }

        ChatOptions.Builder<?> chatOptionsBuilder = ChatOptions.builder()
            .model(modelName);

        if (request.temperature() != null) {
            chatOptionsBuilder.temperature(request.temperature());
        }

        if (request.maxTokens() != null) {
            chatOptionsBuilder.maxTokens(request.maxTokens());
        }

        if (request.topP() != null) {
            chatOptionsBuilder.topP(request.topP());
        }

        return new Prompt(messages, chatOptionsBuilder.build());
    }

    /**
     * Builds the router scoring context (average per-model latency over the last hour, prompt-complexity score, and
     * per-model provider type) shared by the blocking and streaming routing paths. A scorer failure degrades to the
     * most-capable tier (score 1.0) rather than failing the request.
     */
    private AiGatewayRoutingContext buildRoutingContext(
        AiGatewayChatCompletionRequest request, Map<Long, AiModel> modelMap) {

        Map<String, Double> latencyByModelName = aiGatewayRequestLogService.getAverageLatencyByModel(
            Instant.now()
                .minus(Duration.ofHours(1)));

        Map<Long, Double> averageLatencyByModelId = modelMap.entrySet()
            .stream()
            .filter(entry -> latencyByModelName.containsKey(entry.getValue()
                .getName()))
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> latencyByModelName.get(entry.getValue()
                .getName())));

        double promptComplexityScore;

        try {
            promptComplexityScore = promptComplexityScorer.score(request);
        } catch (Exception exception) {
            log.warn("Prompt complexity scoring failed; falling back to most capable tier", exception);

            promptComplexityScore = 1.0;
        }

        Map<String, String> tags = request.tags() != null ? request.tags() : Map.of();

        Map<Long, String> providerTypeByModelId = modelMap.entrySet()
            .stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    AiGatewayProvider provider = aiGatewayProviderService.getProvider(entry.getValue()
                        .getProviderId());

                    return provider.getType()
                        .name();
                }));

        return new AiGatewayRoutingContext(
            averageLatencyByModelId, modelMap, promptComplexityScore, providerTypeByModelId, tags);
    }

    /**
     * Selects the routed deployments for a request that specifies a routing policy: runs the same router selection used
     * on the non-streaming path and returns the primary deployment first, followed by the remaining enabled deployments
     * as ordered fallbacks, together with each deployment's resolved model.
     *
     * <p>
     * The streaming path uses the ordered list with {@code executeStreamWithRetry}, which fails over across deployments
     * only <em>before</em> the first token is emitted (once SSE bytes are flushed they cannot be retracted).
     * </p>
     */
    private RoutedDeployments selectRoutedDeployments(
        AiGatewayChatCompletionRequest request, @Nullable Long connectedUserId) {

        AiGatewayRoutingPolicy routingPolicy = requireRoutingPolicyUsableByConnectedUser(
            aiGatewayRoutingPolicyService.getRoutingPolicyByName(request.routingPolicy()), connectedUserId,
            request.routingPolicy());

        List<AiGatewayModelDeployment> deployments =
            aiGatewayModelDeploymentService.getDeploymentsByRoutingPolicyId(routingPolicy.getId());

        List<AiGatewayModelDeployment> enabledDeployments = deployments.stream()
            .filter(AiGatewayModelDeployment::isEnabled)
            .toList();

        if (enabledDeployments.isEmpty()) {
            throw new IllegalStateException(
                "No enabled deployments for routing policy: " + routingPolicy.getName());
        }

        Map<Long, AiModel> modelMap = enabledDeployments.stream()
            .map(AiGatewayModelDeployment::getModelId)
            .distinct()
            .collect(Collectors.toMap(modelId -> modelId, aiModelService::getModel));

        AiGatewayRoutingContext routingContext = buildRoutingContext(request, modelMap);

        AiGatewayModelDeployment primaryDeployment = aiGatewayRouter.route(
            routingPolicy.getStrategy(), enabledDeployments, routingContext);

        List<AiGatewayModelDeployment> orderedDeployments = new ArrayList<>();

        orderedDeployments.add(primaryDeployment);

        for (AiGatewayModelDeployment deployment : enabledDeployments) {
            if (!deployment.getId()
                .equals(primaryDeployment.getId())) {

                orderedDeployments.add(deployment);
            }
        }

        return new RoutedDeployments(orderedDeployments, modelMap);
    }

    private record RoutedDeployments(
        List<AiGatewayModelDeployment> orderedDeployments, Map<Long, AiModel> modelMap) {
    }

    private record StreamModelResolution(
        ModelResolution modelResolution, @Nullable RoutedDeployments routedDeployments) {
    }

    /**
     * Resolves the model/provider for {@link #chatCompletionStreamInternal}'s routed-vs-direct branch, and logs +
     * rethrows on failure. Extracted purely to keep that method under the checkstyle method-length limit; the try/catch
     * shape, the routed-vs-direct branching, and the BYOK override call ({@link #applyByokOverride}) are otherwise
     * unchanged from before the extraction.
     */
    private StreamModelResolution resolveStreamModelResolution(
        AiGatewayChatCompletionRequest effectiveRequest, Long workspaceId, long startTime,
        @Nullable Long connectedUserId) {

        RoutedDeployments routedDeployments = null;
        ModelResolution modelResolution;

        try {
            // Honor the routing policy on the streaming path too: previously streaming always used the request's
            // literal model and ignored routing entirely. When a routing policy is set the request now streams from
            // the routed deployment and fails over to the next deployment BEFORE the first token is emitted (see
            // AiGatewayRetryHandler.executeStreamWithRetry).
            if (effectiveRequest.routingPolicy() != null) {
                routedDeployments = selectRoutedDeployments(effectiveRequest, connectedUserId);

                AiGatewayModelDeployment primaryDeployment = routedDeployments.orderedDeployments()
                    .get(0);
                AiModel primaryModel = routedDeployments.modelMap()
                    .get(primaryDeployment.getModelId());
                AiGatewayProvider primaryTenantProvider =
                    aiGatewayProviderService.getProvider(primaryModel.getProviderId());
                AiGatewayProvider primaryProvider = applyByokOverride(primaryTenantProvider, connectedUserId);

                modelResolution = new ModelResolution(primaryProvider, primaryModel);
            } else {
                modelResolution = resolveModel(effectiveRequest.model(), connectedUserId);
            }
        } catch (Exception exception) {
            try {
                aiGatewayRequestLogService.create(
                    createErrorLog(effectiveRequest, startTime, exception, connectedUserId), workspaceId);
            } catch (Exception logException) {
                log.error("Failed to log streaming setup error for model '{}'. Original error: {}",
                    effectiveRequest.model(), exception.getMessage(), logException);
            }

            throw exception;
        }

        return new StreamModelResolution(modelResolution, routedDeployments);
    }

    /**
     * Applies the BYOK (bring-your-own-key) override on top of an already-determined tenant provider that a model
     * deployment configured by id ({@code model.getProviderId()}): if {@code connectedUserId} has its own enabled
     * provider of {@code tenantProvider}'s type, that provider serves the request instead of the tenant's — the
     * customer's own credentials, run through the identical
     * {@code AiGatewayChatModelFactory}/{@code AiGatewayEmbeddingModelFactory} call the tenant provider would have
     * taken, so the SSRF guard ({@code AiObservabilityUrlValidator#validateExternalUrl}) and the API-key decryption
     * those factories perform stay on the one path both kinds of provider go through — this method never builds a model
     * client itself.
     *
     * <p>
     * Deliberately calls {@code AiGatewayProviderService#fetchProviderByConnectedUserIdAndType} directly rather than
     * {@link AiGatewayProviderResolver#resolve}: {@code resolve} re-derives its tenant fallback purely by type, which
     * would let it return a DIFFERENT tenant provider row than {@code tenantProvider} in the (schema-permitted, if
     * unusual) case of two enabled tenant providers sharing a type — silently swapping which of the tenant's own
     * accounts serves a model deployment that a routing policy configured by a specific provider id. Falling back to
     * the exact {@code tenantProvider} the caller already resolved avoids that divergence entirely.
     * {@link #resolveModel} has no such already-known provider to preserve — it goes through
     * {@link AiGatewayProviderResolver} directly.
     *
     * <p>
     * Returns {@code tenantProvider} unchanged — with no extra lookup at all — for automation traffic
     * ({@code connectedUserId == null}); every request that reaches this method with a null connected user id takes
     * exactly the code path it took before this override existed. A connected-user provider that exists but is disabled
     * also falls through to {@code tenantProvider} rather than failing the request — the same choice
     * {@link AiGatewayProviderResolver#resolve} documents for the identical situation.
     */
    private AiGatewayProvider applyByokOverride(AiGatewayProvider tenantProvider, @Nullable Long connectedUserId) {
        if (connectedUserId == null) {
            return tenantProvider;
        }

        Optional<AiGatewayProvider> connectedUserProvider = aiGatewayProviderService
            .fetchProviderByConnectedUserIdAndType(connectedUserId, tenantProvider.getType());

        return connectedUserProvider.filter(AiGatewayProvider::isEnabled)
            .orElseGet(() -> requireTenantProviderUsableByConnectedUser(tenantProvider, connectedUserId));
    }

    /**
     * Positive allowlist guarding the fallback branch of {@link #applyByokOverride}: {@code tenantProvider} may only
     * serve {@code connectedUserId}'s request if it is bound to NO connected user (a genuine tenant/shared row) or to
     * this EXACT connected user. A provider bound to a DIFFERENT connected user must never silently serve here —
     * unreachable today because nothing outside raw SQL writes {@code connected_user_id} on a provider, but this guard
     * is what keeps it unreachable once something does: without it, a model deployment whose configured provider
     * happens to be another customer's own BYOK row would run this customer's traffic on that other customer's
     * credentials — a cross-tenant credential leak, not a convenience.
     */
    private AiGatewayProvider requireTenantProviderUsableByConnectedUser(
        AiGatewayProvider tenantProvider, long connectedUserId) {

        Long tenantProviderConnectedUserId = tenantProvider.getConnectedUserId();

        if (tenantProviderConnectedUserId != null && !tenantProviderConnectedUserId.equals(connectedUserId)) {
            throw new AiGatewayProviderScopeViolationException(
                "Provider " + tenantProvider.getId() + " is scoped to a different connected user",
                tenantProvider.getId(), connectedUserId, tenantProviderConnectedUserId);
        }

        return tenantProvider;
    }

    /**
     * Positive-scope guard for a CALLER-SUPPLIED {@code routing_policy} name, mirroring
     * {@link #requireTenantProviderUsableByConnectedUser} for BYOK providers: the resolved policy may only serve
     * {@code connectedUserId}'s request if it is bound to NO connected user (a genuine tenant/shared policy) or to this
     * EXACT connected user. Without this guard, an embedded caller could name ANOTHER customer's connected-user-scoped
     * policy in the request body and route on it — escaping the policy the vendor actually bound to them and bypassing
     * whatever cost control that policy's model tier enforces (spec §9).
     *
     * <p>
     * It only ever sees a caller-supplied name. A name this class resolved from a model or embedded default reaches
     * {@link #applyResolvedRoutingPolicy} first, which applies the same scope test and falls back to direct routing
     * rather than failing the request — an operator's misconfigured default must not take a third party's traffic down
     * with an error naming a policy that caller never supplied.
     *
     * <p>
     * What this guard does NOT do: a workspace-scoped policy carries a null {@code connectedUserId} and therefore
     * passes unchanged. Whether an embedded caller should be able to name a workspace-scoped policy at all is an open
     * product question, deliberately not decided here.
     *
     * <p>
     * Deliberately throws the exact {@link IllegalArgumentException}
     * {@link AiGatewayRoutingPolicyService#getRoutingPolicyByName} itself throws for "not found", with the identical
     * message built from {@code requestedName} rather than the policy's id: a policy that does not exist and a policy
     * belonging to another connected user must be indistinguishable to the caller, or the error message becomes a
     * name-existence oracle. Note that {@code ConnectedUserAiGatewayRoutingPolicyFacadeImpl.bind} deliberately does the
     * OPPOSITE, naming the owning connected user's id outright — it is an admin-only surface whose caller is entitled
     * to see which connected user holds a binding, and being told is how they know to unbind it first. Do not copy that
     * error shape onto a customer-facing path.
     *
     * <p>
     * Returns {@code policy} unchanged for automation traffic ({@code connectedUserId == null}) — every request that
     * reaches this method with a null connected user id takes exactly the code path it took before this guard existed.
     */
    private AiGatewayRoutingPolicy requireRoutingPolicyUsableByConnectedUser(
        AiGatewayRoutingPolicy policy, @Nullable Long connectedUserId, String requestedName) {

        if (connectedUserId == null) {
            return policy;
        }

        Long policyConnectedUserId = policy.getConnectedUserId();

        if (policyConnectedUserId != null && !policyConnectedUserId.equals(connectedUserId)) {
            throw new IllegalArgumentException("Routing policy not found: " + requestedName);
        }

        return policy;
    }

    private ModelResolution resolveModel(String modelIdentifier, @Nullable Long connectedUserId) {
        String[] parts = modelIdentifier.split("/", 2);

        if (parts.length != 2) {
            throw new IllegalArgumentException(
                "Model must be in format 'provider/model', got: " + modelIdentifier);
        }

        String providerTypeName = parts[0].toUpperCase()
            .replace("-", "_");
        String modelName = parts[1];

        AiGatewayProvider tenantProvider = resolveTenantProviderByTypeName(providerTypeName);

        AiModel model = aiModelService.getModel(tenantProvider.getId(), modelName);

        // Unlike applyByokOverride's by-id callers, resolveModel has no already-known specific provider to preserve
        // — it only ever knew a TYPE, so going through the resolver's own customer-then-tenant precedence directly
        // introduces no additional divergence beyond what resolveTenantProviderByTypeName above already accepted.
        AiGatewayProvider provider = connectedUserId == null
            ? tenantProvider
            : aiGatewayProviderResolver.resolve(connectedUserId, tenantProvider.getType());

        return new ModelResolution(provider, model);
    }

    /**
     * Resolves an already-normalized provider type name (uppercased, hyphens replaced with underscores — see
     * {@link #resolveModel}) to the tenant's shared provider of that type, via
     * {@link AiGatewayProviderResolver#resolveTenantProvider}. An unrecognized type name is folded into the same "no
     * enabled provider" error as a recognized type with none configured: from the caller's perspective both mean "there
     * is nothing here that can serve this request", and the pre-BYOK implementation of this method produced that
     * identical message for both cases too (a stream filter that simply never matches an unknown name).
     */
    private AiGatewayProvider resolveTenantProviderByTypeName(String providerTypeName) {
        AiGatewayProviderType providerType;

        try {
            providerType = AiGatewayProviderType.valueOf(providerTypeName);
        } catch (IllegalArgumentException unknownProviderType) {
            throw new IllegalArgumentException(
                "No enabled provider found for type: " + providerTypeName, unknownProviderType);
        }

        return aiGatewayProviderResolver.resolveTenantProvider(providerType);
    }

    private AiGatewayChatCompletionRequest compressMessages(
        AiGatewayChatCompletionRequest request, AiModel model,
        @Nullable AiGatewayProject project) {

        if (!resolveCompressionEnabled(project)) {
            return request;
        }

        if (model.getContextWindow() == null) {
            return request;
        }

        int targetTokens = (int) (model.getContextWindow() * CONTEXT_WINDOW_USAGE_RATIO);

        List<AiGatewayChatMessage> compressedMessages =
            aiGatewayContextCompressor.compress(request.messages(), targetTokens);

        return new AiGatewayChatCompletionRequest(
            request.model(), compressedMessages, request.temperature(), request.maxTokens(),
            request.topP(), request.stream(), request.routingPolicy(), request.cache(),
            request.toolChoice(), request.tools(), request.tags());
    }

    private Message toSpringAiMessage(AiGatewayChatMessage chatMessage) {
        return switch (chatMessage.role()) {
            case SYSTEM -> new SystemMessage(chatMessage.content());
            case ASSISTANT -> {
                if (chatMessage.toolCalls() != null && !chatMessage.toolCalls()
                    .isEmpty()) {
                    List<AssistantMessage.ToolCall> springToolCalls = chatMessage.toolCalls()
                        .stream()
                        .map(
                            toolCall -> new AssistantMessage.ToolCall(
                                toolCall.id(), toolCall.type(), toolCall.function()
                                    .name(),
                                toolCall.function()
                                    .arguments()))
                        .toList();

                    yield AssistantMessage.builder()
                        .content(chatMessage.content() != null ? chatMessage.content() : "")
                        .toolCalls(springToolCalls)
                        .build();
                }

                yield new AssistantMessage(chatMessage.content());
            }
            case TOOL -> {
                ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                    chatMessage.toolCallId(), null,
                    chatMessage.content() != null ? chatMessage.content() : "");

                yield ToolResponseMessage.builder()
                    .responses(List.of(toolResponse))
                    .build();
            }
            case USER -> {
                if (chatMessage.hasContentBlocks()) {
                    yield buildMultimodalUserMessage(chatMessage);
                }

                yield new UserMessage(chatMessage.content());
            }
        };
    }

    private AiGatewayChatCompletionResponse toResponse(ChatResponse chatResponse, String requestedModel) {
        Generation generation = chatResponse.getResult();

        if (generation == null) {
            return new AiGatewayChatCompletionResponse(
                UUID.randomUUID()
                    .toString(),
                "chat.completion",
                System.currentTimeMillis() / 1000, requestedModel, List.of(), null);
        }

        AssistantMessage assistantMessage = generation.getOutput();

        List<AiGatewayChatMessage.ToolCall> toolCalls = null;

        if (assistantMessage.hasToolCalls()) {
            toolCalls = assistantMessage.getToolCalls()
                .stream()
                .map(
                    toolCall -> new AiGatewayChatMessage.ToolCall(
                        toolCall.id(), toolCall.type(),
                        new AiGatewayChatMessage.ToolCallFunction(
                            toolCall.name(), toolCall.arguments())))
                .toList();
        }

        AiGatewayChatMessage responseMessage = new AiGatewayChatMessage(
            AiGatewayChatRole.ASSISTANT, assistantMessage.getText(), toolCalls, null);

        AiGatewayChatCompletionResponse.Choice choice = new AiGatewayChatCompletionResponse.Choice(
            0,
            responseMessage,
            generation.getMetadata()
                .getFinishReason());

        AiGatewayChatCompletionResponse.Usage responseUsage = null;

        if (chatResponse.getMetadata() != null && chatResponse.getMetadata()
            .getUsage() != null) {

            org.springframework.ai.chat.metadata.Usage chatResponseUsage = chatResponse.getMetadata()
                .getUsage();

            responseUsage = new AiGatewayChatCompletionResponse.Usage(
                chatResponseUsage.getPromptTokens(),
                chatResponseUsage.getCompletionTokens(),
                chatResponseUsage.getTotalTokens());
        }

        return new AiGatewayChatCompletionResponse(
            UUID.randomUUID()
                .toString(),
            "chat.completion",
            System.currentTimeMillis() / 1000,
            requestedModel,
            List.of(choice),
            responseUsage);
    }

    private UserMessage buildMultimodalUserMessage(AiGatewayChatMessage chatMessage) {
        StringBuilder textContent = new StringBuilder();
        List<Media> mediaList = new ArrayList<>();

        for (AiGatewayContentBlock block : chatMessage.contentBlocks()) {
            switch (block.type()) {
                case TEXT -> textContent.append(block.text());
                case IMAGE_URL -> {
                    if (block.imageUrl() != null) {
                        String validatedUrl = validateExternalUrl(block.imageUrl()
                            .url());

                        try {
                            mediaList.add(
                                new Media(MimeTypeUtils.IMAGE_PNG, new UrlResource(validatedUrl)));
                        } catch (Exception exception) {
                            throw new IllegalArgumentException(
                                "Invalid image URL: " + block.imageUrl()
                                    .url(),
                                exception);
                        }
                    }
                }
                case IMAGE -> {
                    if (block.imageUrl() != null && block.imageUrl()
                        .url() != null) {
                        String url = block.imageUrl()
                            .url();

                        if (url.startsWith("data:")) {
                            String[] parts = url.split(",", 2);

                            if (parts.length == 2) {
                                String mimeTypePart = parts[0].replace("data:", "")
                                    .replace(";base64", "");
                                byte[] imageBytes = java.util.Base64.getDecoder()
                                    .decode(parts[1]);

                                mediaList.add(
                                    new Media(
                                        MimeTypeUtils.parseMimeType(mimeTypePart),
                                        new ByteArrayResource(imageBytes)));
                            } else {
                                throw new IllegalArgumentException(
                                    "Image block has malformed data URI — expected 'data:<mime>;base64,<data>' format");
                            }
                        } else {
                            String validatedUrl = validateExternalUrl(url);

                            try {
                                mediaList.add(
                                    new Media(MimeTypeUtils.IMAGE_PNG, new UrlResource(validatedUrl)));
                            } catch (Exception exception) {
                                throw new IllegalArgumentException("Invalid image URL: " + url, exception);
                            }
                        }
                    }
                }
                case DOCUMENT -> {
                    if (block.document() != null) {
                        if ("base64".equals(block.document()
                            .sourceType())) {
                            byte[] documentBytes = java.util.Base64.getDecoder()
                                .decode(block.document()
                                    .data());
                            String mediaType =
                                block.document()
                                    .mediaType() != null ? block.document()
                                        .mediaType() : "application/pdf";

                            mediaList.add(
                                new Media(
                                    MimeTypeUtils.parseMimeType(mediaType),
                                    new ByteArrayResource(documentBytes)));
                        } else if ("url".equals(block.document()
                            .sourceType())
                            && block.document()
                                .url() != null) {
                            String validatedDocumentUrl = validateExternalUrl(block.document()
                                .url());

                            try {
                                String mediaType =
                                    block.document()
                                        .mediaType() != null
                                            ? block.document()
                                                .mediaType()
                                            : "application/pdf";

                                mediaList.add(
                                    new Media(
                                        MimeTypeUtils.parseMimeType(mediaType),
                                        new UrlResource(validatedDocumentUrl)));
                            } catch (Exception exception) {
                                throw new IllegalArgumentException(
                                    "Invalid document URL: " + block.document()
                                        .url(),
                                    exception);
                            }
                        } else {
                            throw new IllegalArgumentException(
                                "Document block has unsupported sourceType: " + block.document()
                                    .sourceType() + " — supported: 'base64', 'url'");
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported content block type: " + block.type());
            }
        }

        if (mediaList.isEmpty()) {
            return new UserMessage(textContent.toString());
        }

        return UserMessage.builder()
            .text(textContent.toString())
            .media(mediaList)
            .build();
    }

    @Nullable
    private AiGatewayProject resolveProject(Map<String, String> tags) {
        String projectSlug = tags != null ? tags.get("project_id") : null;
        String workspaceId = tags != null ? tags.get("workspace_id") : null;

        if (projectSlug == null || projectSlug.isBlank() || workspaceId == null) {
            return null;
        }

        return workspaceAiGatewayProjectService.fetchProjectByWorkspaceIdAndSlug(
            Long.parseLong(workspaceId), projectSlug)
            .orElse(null);
    }

    /**
     * Resolves the numeric project id from the request tags (the {@code project_id} tag is a per-workspace slug), or
     * {@code null} when the request is not attributed to a project. Used to layer project-scoped guardrail overrides.
     */
    private @Nullable Long resolveProjectId(Map<String, String> tags) {
        AiGatewayProject project = resolveProject(tags);

        return project != null ? project.getId() : null;
    }

    private boolean resolveCompressionEnabled(@Nullable AiGatewayProject project) {
        if (project != null && project.getCompressionEnabled() != null) {
            return project.getCompressionEnabled();
        }

        return true; // system default
    }

    private void setProjectIdFromProject(AiLlmUsage requestLog, @Nullable AiGatewayProject project) {
        if (project != null) {
            requestLog.setProjectId(project.getId());
        }
    }

    private static Long resolveWorkspaceIdFromTags(Map<String, String> tags) {
        if (tags == null || !tags.containsKey("workspace_id")) {
            return null;
        }

        return Long.parseLong(tags.get("workspace_id"));
    }

    private int[] extractTokenCounts(ChatResponse chatResponse) {
        int inputTokens = 0;
        int outputTokens = 0;

        if (chatResponse.getMetadata() != null && chatResponse.getMetadata()
            .getUsage() != null) {

            inputTokens = (int) Math.min(
                chatResponse.getMetadata()
                    .getUsage()
                    .getPromptTokens(),
                Integer.MAX_VALUE);
            outputTokens = (int) Math.min(
                chatResponse.getMetadata()
                    .getUsage()
                    .getCompletionTokens(),
                Integer.MAX_VALUE);
        }

        return new int[] {
            inputTokens, outputTokens
        };
    }

    private AiLlmUsage createSuccessLog(
        AiGatewayChatCompletionRequest request, AiModel model, AiGatewayProvider provider,
        long startTime, int inputTokens, int outputTokens, @Nullable Long connectedUserId) {

        AiLlmUsage requestLog = new AiLlmUsage(
            UUID.randomUUID()
                .toString(),
            request.model());

        requestLog.setRoutedModel(model.getName());
        requestLog.setRoutedProvider(provider.getType()
            .name());
        requestLog.setLatencyMs((int) (System.currentTimeMillis() - startTime));
        requestLog.setStatus(200);
        requestLog.setInputTokens(inputTokens);
        requestLog.setOutputTokens(outputTokens);

        BigDecimal cost = aiGatewayCostCalculator.calculateCost(model, inputTokens, outputTokens);

        requestLog.setCost(cost);

        setApiKeyIdFromAuthentication(requestLog);

        // The spend rollup key: ai_llm_usage.user_id already exists and is otherwise left null for every AI_GATEWAY
        // row (see AiLlmUsage's Javadoc — the column was added for AI Hub attribution and is generic "the user this
        // call ran under"). Stamping the resolved embedded connected user id here, with no schema change, is what
        // makes per-customer spend attribution derivable straight from ai_llm_usage (spec §9); connectedUserId is
        // null for every automation request and for an embedded request that never resolved one, so this is a no-op
        // for existing traffic.
        requestLog.setUserId(connectedUserId);

        return requestLog;
    }

    /**
     * Reads the authenticated {@code AiGatewayApiKeyAuthenticationToken} from the security context (if any) and stamps
     * the api-key id on the request log. No-ops for non-API-key authentication (e.g. internal session-based callers).
     */
    private static void setApiKeyIdFromAuthentication(AiLlmUsage requestLog) {
        Long apiKeyId = resolveAuthenticatedApiKeyId();

        if (apiKeyId != null) {
            requestLog.setApiKeyId(apiKeyId);
        }
    }

    /**
     * Returns the authenticated API key id from the security context, or {@code null} when the current call is not
     * authenticated via {@code AiGatewayApiKeyAuthenticationToken} (e.g. internal session-based callers, tests).
     */
    private static Long resolveAuthenticatedApiKeyId() {
        org.springframework.security.core.context.SecurityContext context = SecurityContextHolder.getContext();

        if (context == null) {
            return null;
        }

        org.springframework.security.core.Authentication authentication = context.getAuthentication();

        if (authentication instanceof com.bytechef.ee.automation.ai.gateway.security.web.authentication.AiGatewayApiKeyAuthenticationToken token) {
            return token.getApiKeyId();
        }

        return null;
    }

    /**
     * Returns the environment id the current gateway API key was issued for, or {@code 0} (the {@code DEVELOPMENT}
     * ordinal) when the current call is not authenticated via {@code AiGatewayApiKeyAuthenticationToken} — e.g. an
     * internal caller with no security context. Used to scope {@link #resolveEmbeddedDefaultRoutingPolicyId}, which
     * short-circuits to {@code null} whenever {@code connectedUserId} is {@code null}, so this fallback never affects
     * automation traffic.
     */
    private static long resolveAuthenticatedEnvironmentId() {
        org.springframework.security.core.context.SecurityContext context = SecurityContextHolder.getContext();

        if (context == null) {
            return 0L;
        }

        org.springframework.security.core.Authentication authentication = context.getAuthentication();

        if (authentication instanceof com.bytechef.ee.automation.ai.gateway.security.web.authentication.AiGatewayApiKeyAuthenticationToken token) {
            return token.getEnvironmentId();
        }

        return 0L;
    }

    private AiLlmUsage createErrorLog(
        AiGatewayChatCompletionRequest request, long startTime, Exception exception,
        @Nullable Long connectedUserId) {

        AiLlmUsage errorLog = new AiLlmUsage(
            UUID.randomUUID()
                .toString(),
            request.model());

        errorLog.setLatencyMs((int) (System.currentTimeMillis() - startTime));
        errorLog.setStatus(resolveErrorStatus(exception));
        errorLog.setErrorMessage(exception.getMessage());

        setApiKeyIdFromAuthentication(errorLog);

        // See createSuccessLog's comment on the same field — same spend rollup key, error rows included so a
        // customer's failed-request costs (retries, error-status rows) attribute the same way as successful ones.
        errorLog.setUserId(connectedUserId);

        return errorLog;
    }

    private static BigDecimal calculateFallbackCost(AiModel model, int inputTokens, int outputTokens) {
        BigDecimal millionTokens = BigDecimal.valueOf(1_000_000);

        BigDecimal inputRate = model.getInputCostPerMTokens();
        BigDecimal outputRate = model.getOutputCostPerMTokens();

        if (inputRate == null || outputRate == null) {
            log.error(
                "Model '{}' (id={}) has incomplete cost configuration (input={}, output={}) — " +
                    "using conservative default rate of ${}/M tokens for missing rates to prevent budget bypass. " +
                    "Configure pricing to fix this.",
                model.getName(), model.getId(), inputRate, outputRate, DEFAULT_FALLBACK_COST_PER_M_TOKENS);

            if (inputRate == null) {
                inputRate = DEFAULT_FALLBACK_COST_PER_M_TOKENS;
            }

            if (outputRate == null) {
                outputRate = DEFAULT_FALLBACK_COST_PER_M_TOKENS;
            }
        }

        BigDecimal inputCost = inputRate
            .multiply(BigDecimal.valueOf(inputTokens))
            .divide(millionTokens, 10, java.math.RoundingMode.HALF_UP);

        BigDecimal outputCost = outputRate
            .multiply(BigDecimal.valueOf(outputTokens))
            .divide(millionTokens, 10, java.math.RoundingMode.HALF_UP);

        return inputCost.add(outputCost);
    }

    private static int resolveErrorStatus(Exception exception) {
        if (exception instanceof IllegalArgumentException) {
            return 400;
        }

        if (exception instanceof BudgetExceededException) {
            return 429;
        }

        if (exception instanceof IllegalStateException) {
            return 503;
        }

        return 500;
    }

    /**
     * Persists the trace row and, on success, a per-generation span for this exchange. {@code response}, when
     * non-{@code null}, must be the SCANNED-but-not-yet-restored response (see {@code chatCompletion}'s call site) —
     * both the trace's {@code output} and the span's {@code output} are read off it, and neither ever digests the span,
     * so a restored response here would persist this exchange's real PII values into the span store even on a workspace
     * with PII redaction/tokenization enabled.
     */
    private void processTracingHeaders(
        AiObservabilityTracingHeaders tracingHeaders,
        @Nullable Long workspaceId,
        AiGatewayChatCompletionRequest request,
        @Nullable AiGatewayChatCompletionResponse response,
        long startTime,
        boolean success,
        @Nullable ResolvedPrompt resolvedPrompt,
        @Nullable Long connectedUserId) {

        if (workspaceId == null) {
            return;
        }

        try {
            String inputText = request.messages()
                .stream()
                .map(message -> message.content() != null ? message.content() : "")
                .reduce("", (accumulator, content) -> accumulator + content);
            String outputText = null;

            int inputTokens = 0;
            int outputTokens = 0;

            if (response != null && response.usage() != null) {
                inputTokens = response.usage()
                    .promptTokens();
                outputTokens = response.usage()
                    .completionTokens();
            }

            if (response != null && response.choices() != null && !response.choices()
                .isEmpty()) {

                AiGatewayChatCompletionResponse.Choice firstChoice = response.choices()
                    .getFirst();

                if (firstChoice.message() != null) {
                    outputText = firstChoice.message()
                        .content();
                }
            }

            int latencyMs = (int) (System.currentTimeMillis() - startTime);

            String[] modelParts = request.model()
                .split("/", 2);
            String providerName = modelParts.length > 0 ? modelParts[0] : request.model();
            String modelName = modelParts.length > 1 ? modelParts[1] : request.model();

            BigDecimal cost = calculateTraceCost(request.model(), inputTokens, outputTokens);

            AiGatewayProject project = resolveProject(request.tags());

            AiObservabilityTrace trace = resolveOrCreateTrace(
                tracingHeaders, workspaceId, modelName, inputText, outputText, inputTokens, outputTokens,
                latencyMs, cost, success, project);

            AiObservabilitySpan span = new AiObservabilitySpan(trace.getId(), AiObservabilitySpanType.GENERATION);

            span.setStartTime(Instant.ofEpochMilli(startTime));
            span.setName(tracingHeaders.spanName() != null ? tracingHeaders.spanName() : modelName);
            span.setModel(modelName);
            span.setProvider(providerName);
            span.setInput(inputText);
            span.setOutput(outputText);
            span.setInputTokens(inputTokens);
            span.setOutputTokens(outputTokens);
            span.setLatencyMs(latencyMs);
            span.setCost(cost);

            Instant spanEndTime = Instant.now();

            if (tracingHeaders.parentSpanId() != null) {
                try {
                    span.setParentSpanId(Long.parseLong(tracingHeaders.parentSpanId()));
                } catch (NumberFormatException numberFormatException) {
                    log.warn("Invalid parentSpanId header value '{}' — ignoring",
                        tracingHeaders.parentSpanId());
                }
            }

            if (resolvedPrompt != null) {
                span.setPromptId(resolvedPrompt.promptId());
                span.setPromptVersionId(resolvedPrompt.promptVersionId());
            }

            applyConnectedUserSpanAttribute(span, connectedUserId);

            span.close(spanEndTime, success ? AiObservabilitySpanStatus.COMPLETED : AiObservabilitySpanStatus.ERROR);

            aiObservabilitySpanService.create(span);

            if (trace.getStatus() == AiObservabilityTraceStatus.COMPLETED
                || trace.getStatus() == AiObservabilityTraceStatus.ERROR) {

                try {
                    publishTraceCompletedEvent(trace, request.model());
                } catch (Exception publishException) {
                    log.error(
                        "Failed to publish trace-completed event for model '{}' (traceId={})",
                        request.model(), trace.getId(), publishException);
                }
            }

            if (trace.getStatus() == AiObservabilityTraceStatus.COMPLETED) {
                try {
                    aiEvalExecutor.evaluateTrace(trace.getId(), workspaceId);
                } catch (Exception evalException) {
                    log.error(
                        "Failed to dispatch eval for model '{}' (traceId={})",
                        request.model(), trace.getId(), evalException);
                }
            }
        } catch (Exception exception) {
            log.error(
                "Failed to persist trace/span for model '{}' — tracing data will be missing",
                request.model(), exception);
        }
    }

    private void publishTraceCompletedEvent(AiObservabilityTrace trace, String modelName) {
        applicationEventPublisher.publishEvent(new AiGatewayTraceCompletedEvent(
            workspaceAiObservabilityTraceService.getWorkspaceId(trace.getId()),
            trace.getId(),
            trace.getExternalTraceId(),
            modelName,
            trace.getTotalInputTokens(),
            trace.getTotalOutputTokens(),
            trace.getTotalLatencyMs(),
            trace.getTotalCost(),
            trace.getStatus() == AiObservabilityTraceStatus.COMPLETED,
            Instant.now()));
    }

    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    private void processStreamingTracingHeaders(
        AiObservabilityTracingHeaders tracingHeaders,
        @Nullable Long workspaceId,
        AiGatewayChatCompletionRequest request,
        AiModel model,
        AiGatewayProvider provider,
        int inputTokens,
        int outputTokens,
        long startTime,
        boolean success,
        @Nullable String outputText,
        @Nullable AtomicLong traceIdHolder,
        @Nullable Long connectedUserId) {

        if (workspaceId == null) {
            return;
        }

        try {
            String inputText = request.messages()
                .stream()
                .map(message -> message.content() != null ? message.content() : "")
                .reduce("", (accumulator, content) -> accumulator + content);

            int latencyMs = (int) (System.currentTimeMillis() - startTime);

            BigDecimal cost = calculateTraceCost(request.model(), inputTokens, outputTokens);

            AiGatewayProject project = resolveProject(request.tags());

            AiObservabilityTrace trace = resolveOrCreateTrace(
                tracingHeaders, workspaceId, model.getName(), inputText, outputText, inputTokens, outputTokens,
                latencyMs, cost, success, project);

            if (traceIdHolder != null && trace.getId() != null) {
                traceIdHolder.set(trace.getId());
            }

            AiObservabilitySpan span = new AiObservabilitySpan(trace.getId(), AiObservabilitySpanType.GENERATION);

            span.setStartTime(Instant.ofEpochMilli(startTime));
            span.setName(tracingHeaders.spanName() != null ? tracingHeaders.spanName() : model.getName());
            span.setModel(model.getName());
            span.setProvider(provider.getType()
                .name());
            span.setInput(inputText);
            span.setOutput(outputText);
            span.setInputTokens(inputTokens);
            span.setOutputTokens(outputTokens);
            span.setLatencyMs(latencyMs);
            span.setCost(cost);

            Instant spanEndTime = Instant.now();

            if (tracingHeaders.parentSpanId() != null) {
                try {
                    span.setParentSpanId(Long.parseLong(tracingHeaders.parentSpanId()));
                } catch (NumberFormatException numberFormatException) {
                    log.warn("Invalid parentSpanId header value '{}' — ignoring",
                        tracingHeaders.parentSpanId());
                }
            }

            applyConnectedUserSpanAttribute(span, connectedUserId);

            span.close(spanEndTime, success ? AiObservabilitySpanStatus.COMPLETED : AiObservabilitySpanStatus.ERROR);

            transactionTemplate.executeWithoutResult(
                status -> aiObservabilitySpanService.create(span));
        } catch (Exception exception) {
            log.error("Failed to process streaming tracing headers for model '{}' — tracing data will be missing",
                request.model(), exception);
        }
    }

    /**
     * Stamps the resolved embedded connected user id onto the span's existing {@code metadata} column as an attribute —
     * per spec §9, spans already persist {@code input}, {@code output}, {@code model}, {@code cost} and
     * {@code latencyMs}; they gain the connected user as an attribute on this call, not a new span type or a schema
     * change. {@code metadata} is otherwise unused by every span the gateway itself creates (the trace-level metadata
     * populated from {@code X-ByteChef-Metadata-*} headers is a separate field on {@link AiObservabilityTrace}, not
     * this column), so this never collides with another writer. No-op when {@code connectedUserId} is {@code null} —
     * automation traffic and a header-less embedded request leave the span exactly as before.
     */
    private static void applyConnectedUserSpanAttribute(AiObservabilitySpan span, @Nullable Long connectedUserId) {
        if (connectedUserId != null) {
            span.setMetadata("{\"connectedUserId\":" + connectedUserId + "}");
        }
    }

    private void processEmbeddingTracingHeaders(
        AiObservabilityTracingHeaders tracingHeaders,
        @Nullable Long workspaceId,
        AiGatewayEmbeddingRequest request,
        int inputTokens,
        long startTime,
        boolean success) {

        if (workspaceId == null) {
            return;
        }

        try {
            String inputText = String.join(", ", request.input());

            int latencyMs = (int) (System.currentTimeMillis() - startTime);

            String[] modelParts = request.model()
                .split("/", 2);
            String providerName = modelParts.length > 0 ? modelParts[0] : request.model();
            String modelName = modelParts.length > 1 ? modelParts[1] : request.model();

            BigDecimal cost = calculateTraceCost(request.model(), inputTokens, 0);

            AiObservabilityTrace trace = resolveOrCreateTrace(
                tracingHeaders, workspaceId, modelName, inputText, null, inputTokens, 0,
                latencyMs, cost, success, null);

            AiObservabilitySpan span = new AiObservabilitySpan(trace.getId(), AiObservabilitySpanType.GENERATION);

            span.setStartTime(Instant.ofEpochMilli(startTime));
            span.setName(tracingHeaders.spanName() != null ? tracingHeaders.spanName() : modelName);
            span.setModel(modelName);
            span.setProvider(providerName);
            span.setInput(inputText);
            span.setInputTokens(inputTokens);
            span.setLatencyMs(latencyMs);
            span.setCost(cost);

            Instant spanEndTime = Instant.now();

            if (tracingHeaders.parentSpanId() != null) {
                try {
                    span.setParentSpanId(Long.parseLong(tracingHeaders.parentSpanId()));
                } catch (NumberFormatException numberFormatException) {
                    log.warn("Invalid parentSpanId header value '{}' — ignoring",
                        tracingHeaders.parentSpanId());
                }
            }

            span.close(spanEndTime, success ? AiObservabilitySpanStatus.COMPLETED : AiObservabilitySpanStatus.ERROR);

            aiObservabilitySpanService.create(span);
        } catch (Exception exception) {
            log.error("Failed to process tracing headers for embedding model '{}' — tracing data will be missing",
                request.model(), exception);
        }
    }

    private AiObservabilityTrace resolveOrCreateTrace(
        AiObservabilityTracingHeaders tracingHeaders,
        Long workspaceId,
        String modelName,
        String inputText,
        @Nullable String outputText,
        int inputTokens,
        int outputTokens,
        int latencyMs,
        @Nullable BigDecimal cost,
        boolean success,
        @Nullable AiGatewayProject project) {

        String externalTraceId = tracingHeaders.traceId();

        if (tracingHeaders.hasExternalTraceId()) {
            AiObservabilityTrace existingTrace = workspaceAiObservabilityTraceService
                .findByExternalTraceId(workspaceId, externalTraceId)
                .orElse(null);

            if (existingTrace != null) {
                Integer existingInputTokens = existingTrace.getTotalInputTokens();
                Integer existingOutputTokens = existingTrace.getTotalOutputTokens();
                Integer existingLatencyMs = existingTrace.getTotalLatencyMs();
                BigDecimal existingCost = existingTrace.getTotalCost();

                existingTrace.setTotalInputTokens(
                    (existingInputTokens != null ? existingInputTokens : 0) + inputTokens);
                existingTrace.setTotalOutputTokens(
                    (existingOutputTokens != null ? existingOutputTokens : 0) + outputTokens);
                existingTrace.setTotalLatencyMs(
                    (existingLatencyMs != null ? existingLatencyMs : 0) + latencyMs);

                if (cost != null) {
                    existingTrace.setTotalCost(
                        existingCost != null ? existingCost.add(cost) : cost);
                }

                if (!success) {
                    existingTrace.setStatus(AiObservabilityTraceStatus.ERROR);
                }

                aiObservabilityTraceService.update(existingTrace);

                return existingTrace;
            }
        }

        boolean redact = isPiiRedactionEnabled(workspaceId);

        AiObservabilityTrace trace = new AiObservabilityTrace(tracingHeaders.source());

        trace.setExternalTraceId(externalTraceId);
        trace.setName(tracingHeaders.spanName() != null ? tracingHeaders.spanName() : modelName);
        trace.setInput(redact ? redactedDigest(inputText) : inputText);
        trace.setOutput(redact ? redactedDigest(outputText) : outputText);
        trace.setPiiRedacted(redact);
        trace.setApiKeyId(resolveAuthenticatedApiKeyId());
        trace.setTotalInputTokens(inputTokens);
        trace.setTotalOutputTokens(outputTokens);
        trace.setTotalLatencyMs(latencyMs);
        trace.setTotalCost(cost);
        trace.setUserId(tracingHeaders.userId());
        trace.setStatus(success ? AiObservabilityTraceStatus.COMPLETED : AiObservabilityTraceStatus.ERROR);

        if (!tracingHeaders.metadata()
            .isEmpty()) {

            StringBuilder metadataJson = new StringBuilder("{");
            boolean first = true;

            for (Map.Entry<String, String> entry : tracingHeaders.metadata()
                .entrySet()) {

                if (!first) {
                    metadataJson.append(",");
                }

                metadataJson.append("\"")
                    .append(entry.getKey()
                        .replace("\"", "\\\""))
                    .append("\":\"")
                    .append(entry.getValue()
                        .replace("\"", "\\\""))
                    .append("\"");
                first = false;
            }

            metadataJson.append("}");

            trace.setMetadata(metadataJson.toString());
        }

        if (project != null) {
            trace.setProjectId(project.getId());
        }

        if (tracingHeaders.sessionId() != null) {
            AiObservabilitySession session = workspaceAiObservabilitySessionService.getOrCreateSessionByExternalId(
                workspaceId, tracingHeaders.sessionId(), project != null ? project.getId() : null,
                tracingHeaders.userId());

            trace.setSessionId(session.getId());
        }

        if (!tracingHeaders.tagNames()
            .isEmpty()) {

            // Resolve incoming tag names against the platform `tag` table — TagService.save() does the
            // find-or-create-by-name dance. Tags are workspace-agnostic global rows; the workspace dimension is
            // carried by the using row (the trace itself), matching project_tag's pattern.
            List<com.bytechef.platform.tag.domain.Tag> savedTags = tagService.save(
                tracingHeaders.tagNames()
                    .stream()
                    .map(com.bytechef.platform.tag.domain.Tag::new)
                    .toList());

            Set<AiObservabilityTraceTag> traceTags = savedTags.stream()
                .map(tag -> new AiObservabilityTraceTag(tag.getId()))
                .collect(Collectors.toCollection(HashSet::new));

            trace.setTags(traceTags);
        }

        // Under READ_COMMITTED two concurrent requests with the same external trace id can both see an empty
        // findByExternalTraceId and both attempt insert. The UNIQUE constraint turns the loser's insert into a
        // DuplicateKeyException; we resolve the race by re-finding and merging this request's usage onto the
        // winning row instead of bubbling a 500. Narrowed to DuplicateKeyException (the JDBC-translated subclass
        // of DataIntegrityViolationException) AND to the specific external-trace-id constraint name so that a
        // future NOT-NULL or FK addition surfaces as a real 500 instead of being silently absorbed as a race —
        // the orElseThrow fallback would otherwise mask the schema bug under "duplicate but re-fetch returned
        // empty." Mirrors the discipline applied in AiObservabilityOtlpIngestFacadeImpl.
        if (tracingHeaders.hasExternalTraceId()) {
            try {
                workspaceAiObservabilityTraceService.createInWorkspace(trace, workspaceId);
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                if (!AiGatewayConstraintMatchers.matchesConstraint(
                    duplicate, TRACE_DEDUP_INDEX, TRACE_DEDUP_INDEX_PATTERN)) {

                    throw duplicate;
                }

                AiObservabilityTrace winner = workspaceAiObservabilityTraceService
                    .findByExternalTraceId(workspaceId, externalTraceId)
                    .orElseThrow(() -> duplicate);

                mergeIntoExistingTrace(winner, trace);

                aiObservabilityTraceService.update(winner);

                return winner;
            }
        } else {
            workspaceAiObservabilityTraceService.createInWorkspace(trace, workspaceId);
        }

        return trace;
    }

    /**
     * Returns {@code true} when the workspace's {@code cacheEnabled} setting is unset or true. Defaults to true so
     * workspaces without explicit settings keep the gateway's existing cache behavior. Callers must still gate on
     * {@link AiGatewayResponseCache#shouldCache} for the request-level checks (deterministic, non-streaming), and on
     * {@link #isCacheable} for the combined decision including PII tokenization.
     */
    private boolean isWorkspaceCachingEnabled(@Nullable Map<String, String> tags) {
        if (tags == null || !tags.containsKey("workspace_id")) {
            return true;
        }

        try {
            long workspaceId = Long.parseLong(tags.get("workspace_id"));

            return aiGatewayWorkspaceSettingsService.findByWorkspaceId(workspaceId)
                .map(settings -> settings.cacheEnabled() == null || Boolean.TRUE.equals(settings.cacheEnabled()))
                .orElse(true);
        } catch (NumberFormatException malformed) {
            return true;
        }
    }

    /**
     * Returns {@code true} when {@code request} may be read from or written to the response cache: workspace caching is
     * enabled, {@link AiGatewayResponseCache#shouldCache} agrees, AND {@code request} carries no PII token.
     *
     * <p>
     * PII tokenization makes the response cache's existing key composition unsafe for a tokenized request in TWO
     * distinct ways that both had to be closed, not just one:
     * </p>
     * <ol>
     * <li>Keying on the tokenized request (the pre-tokenization behavior, unchanged): each session mints a fresh random
     * {@code sessionId} per exchange, so the same PII value now hashes to a different key on every request. The cache
     * can never hit for a PII-bearing prompt once PII redaction is enabled, and every miss still calls
     * {@link AiGatewayResponseCache#put}, permanently filling a shared cache with single-use entries that evict
     * reusable ones for no benefit. This method's PII-token check exists specifically to stop that: a request carrying
     * a token is simply never cached, on either the read or the write side.</li>
     * <li>Keying on the PRE-tokenization request instead (the obvious-looking "fix" for the above — do NOT do this):
     * before tokenization, two different users' different PII values redacted to the identical {@code [REDACTED_EMAIL]}
     * placeholder, so they legitimately shared one cache key and one cached response — safe, because the cached
     * response itself never held a real value. Now that responses are restored with real values before being returned,
     * a cache entry keyed on pre-tokenization content would let one user's session restore and serve BACK ANOTHER
     * USER'S real PII value that a completely different request happened to trigger — a cross-session PII leak. This is
     * why the fix is "skip the cache", not "key on something more stable".</li>
     * </ol>
     *
     * @see PiiToken#pattern()
     */
    private boolean isCacheable(AiGatewayChatCompletionRequest request) {
        return isWorkspaceCachingEnabled(request.tags()) && aiGatewayResponseCache.shouldCache(request)
            && !containsPiiToken(request);
    }

    /**
     * Returns {@code true} when any USER/SYSTEM/ASSISTANT message content in {@code request} contains a
     * {@link PiiToken}-shaped span — i.e. {@code request} has already been through session-carrying PII tokenization
     * (see {@link #chatCompletion}). Used by {@link #isCacheable} to keep a tokenized request out of the response cache
     * entirely; see that method's javadoc for why.
     */
    private static boolean containsPiiToken(AiGatewayChatCompletionRequest request) {
        for (AiGatewayChatMessage message : request.messages()) {
            String content = message.content();

            if (content != null && PiiToken.pattern()
                .matcher(content)
                .find()) {

                return true;
            }
        }

        return false;
    }

    /**
     * Reads the workspace's guardrails {@code redactPii} setting (defaults to false). A missing workspace id is treated
     * as "don't redact" — workspace-unattributed traces are already at the edge of the observability contract. The
     * guardrail fields moved off {@link AiGatewayWorkspaceSettings} onto the standalone
     * {@code AiGuardrailsWorkspaceSettings} with the guardrail-engine extraction, so this reads through that service
     * directly rather than {@link #aiGatewayWorkspaceSettingsService}.
     */
    private boolean isPiiRedactionEnabled(@Nullable Long workspaceId) {
        if (workspaceId == null) {
            return false;
        }

        return aiGuardrailsWorkspaceSettingsService.fetchSettings(workspaceId)
            .map(settings -> Boolean.TRUE.equals(settings.redactPii()))
            .orElse(false);
    }

    /**
     * Returns a short placeholder that records the fact of content + its length + a SHA-256 digest so operators can
     * distinguish payloads without seeing them. Null/empty input stays null/empty.
     */
    private static String redactedDigest(@Nullable String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes);

            StringBuilder hex = new StringBuilder(hash.length * 2);

            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }

            return String.format("[redacted len=%d sha256=%s]", bytes.length, hex.substring(0, 16));
        } catch (java.security.NoSuchAlgorithmException noSha256) {
            // SHA-256 is guaranteed in JDK 8+; fall back to length-only rather than bubble.
            return String.format("[redacted len=%d]", bytes.length);
        }
    }

    private static void mergeIntoExistingTrace(AiObservabilityTrace winner, AiObservabilityTrace current) {
        Integer existingInputTokens = winner.getTotalInputTokens();
        Integer existingOutputTokens = winner.getTotalOutputTokens();
        Integer existingLatencyMs = winner.getTotalLatencyMs();
        BigDecimal existingCost = winner.getTotalCost();

        Integer addInputTokens = current.getTotalInputTokens();
        Integer addOutputTokens = current.getTotalOutputTokens();
        Integer addLatencyMs = current.getTotalLatencyMs();
        BigDecimal addCost = current.getTotalCost();

        winner.setTotalInputTokens(
            (existingInputTokens != null ? existingInputTokens : 0) + (addInputTokens != null ? addInputTokens : 0));
        winner.setTotalOutputTokens(
            (existingOutputTokens != null ? existingOutputTokens : 0) +
                (addOutputTokens != null ? addOutputTokens : 0));
        winner.setTotalLatencyMs(
            (existingLatencyMs != null ? existingLatencyMs : 0) + (addLatencyMs != null ? addLatencyMs : 0));

        if (addCost != null) {
            winner.setTotalCost(existingCost != null ? existingCost.add(addCost) : addCost);
        }

        if (current.getStatus() == AiObservabilityTraceStatus.ERROR) {
            winner.setStatus(AiObservabilityTraceStatus.ERROR);
        }
    }

    /**
     * Computes the cost of a trace. Returns {@code null} only when the cost cannot be computed; callers MUST treat
     * {@code null} as "unknown" and never as zero — cost-based dashboards and alert rules should filter/exclude rows
     * where cost is null rather than summing them as $0.
     */
    @Nullable
    private BigDecimal calculateTraceCost(String modelIdentifier, int inputTokens, int outputTokens) {
        try {
            // No connected user id: only the resolved model's pricing metadata is read below, never the provider, so
            // a BYOK override here would be resolved and then silently discarded — skip it.
            ModelResolution modelResolution = resolveModel(modelIdentifier, null);

            return aiGatewayCostCalculator.calculateCost(
                modelResolution.model(), inputTokens, outputTokens);
        } catch (Exception exception) {
            log.error(
                "Failed to calculate cost for trace — model '{}'; persisting null (unknown) cost. " +
                    "Cost dashboards and cost-based alert rules MUST exclude null cost rows.",
                modelIdentifier, exception);

            return null;
        }
    }

    /**
     * Validates that the URL points to a public, non-internal host and returns the input URL unchanged. Delegates to
     * {@link AiObservabilityUrlValidator} which is the shared SSRF guard used across the gateway (webhook delivery,
     * provider connectivity tests, image/document URL resolution). Earlier revisions rewrote the URL to a resolved IP
     * literal to defeat DNS rebinding, but that broke HTTPS SNI and is now an accepted residual risk (see
     * {@link AiObservabilityUrlValidator} Javadoc).
     */
    static String validateExternalUrl(String url) {
        AiObservabilityUrlValidator.validateExternalUrl(url);

        return url;
    }

    private AiGatewayChatCompletionRequest prependSystemMessage(
        AiGatewayChatCompletionRequest request, String systemContent) {

        List<AiGatewayChatMessage> existingMessages = request.messages();

        List<AiGatewayChatMessage> updatedMessages = new ArrayList<>();

        updatedMessages.add(new AiGatewayChatMessage(AiGatewayChatRole.SYSTEM, systemContent));

        for (AiGatewayChatMessage message : existingMessages) {
            if (message.role() != AiGatewayChatRole.SYSTEM) {
                updatedMessages.add(message);
            }
        }

        return new AiGatewayChatCompletionRequest(
            request.model(), updatedMessages, request.temperature(), request.maxTokens(),
            request.topP(), request.stream(), request.routingPolicy(),
            request.cache(), request.toolChoice(), request.tools(), request.tags());
    }

    private ResolvedPrompt resolvePrompt(
        @Nullable AiPromptHeaders promptHeaders, Long workspaceId,
        AiGatewayChatCompletionRequest request) {

        if (promptHeaders == null || !promptHeaders.hasPromptEnabled()) {
            return null;
        }

        AiGatewayProject project = resolveProject(request.tags());

        Long projectId = project != null ? project.getId() : null;

        Optional<AiPrompt> promptOptional = workspaceAiPromptService.getPromptByName(
            workspaceId, projectId, promptHeaders.promptName());

        if (promptOptional.isEmpty()) {
            throw new IllegalArgumentException(
                "Prompt not found: " + promptHeaders.promptName());
        }

        AiPrompt prompt = promptOptional.get();

        String environment = promptHeaders.resolvedEnvironment();

        Optional<AiPromptVersion> activeVersionOptional =
            aiPromptVersionService.getActiveVersion(prompt.getId(), environment);

        if (activeVersionOptional.isEmpty()) {
            throw new IllegalArgumentException(
                "No active prompt version found for prompt '" + promptHeaders.promptName() +
                    "' in environment '" + environment + "'");
        }

        AiPromptVersion activeVersion = activeVersionOptional.get();

        Map<String, Object> requestVariables = request.tags() != null
            ? new java.util.HashMap<>(request.tags())
            : Map.of();

        String resolvedContent = substituteVariables(activeVersion.getContent(), requestVariables);

        return new ResolvedPrompt(prompt.getId(), activeVersion.getId(), resolvedContent);
    }

    private String substituteVariables(String content, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return content;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String variableName = matcher.group(1);
            Object value = variables.get(variableName);
            String replacement = value != null ? Matcher.quoteReplacement(value.toString()) : matcher.group(0);

            matcher.appendReplacement(result, replacement);
        }

        matcher.appendTail(result);

        return result.toString();
    }

    private record ModelResolution(AiGatewayProvider provider, AiModel model) {
    }

    private record ResolvedPrompt(Long promptId, Long promptVersionId, String content) {
    }
}
