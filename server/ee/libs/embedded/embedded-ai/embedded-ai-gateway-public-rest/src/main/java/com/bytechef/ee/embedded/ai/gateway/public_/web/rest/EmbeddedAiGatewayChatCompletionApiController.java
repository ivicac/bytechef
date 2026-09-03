/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.ai.gateway.public_.web.rest;

import com.bytechef.ee.automation.ai.gateway.facade.AiGatewayFacade;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ChatCompletionRequestModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ChatCompletionResponseModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ChatMessageModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ChoiceModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ContentBlockImageUrlModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ContentBlockModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.StreamErrorChunkModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ToolCallFunctionModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ToolCallModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ToolFunctionModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.ToolModel;
import com.bytechef.ee.embedded.ai.gateway.public_.web.rest.model.UsageModel;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.domain.BudgetExceededException;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionResponse;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayContentBlock;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayContentBlockType;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayTool;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayToolChoice;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.security.web.authentication.PrincipalEnvironment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * The embedded surface's own chat-completion endpoint, disjoint from the automation gateway's
 * {@code /api/ai-gateway/v1/chat/completions}. The connected user comes from authentication rather than a
 * caller-settable header: the route falls under {@code EmbeddedApiKeySecurityConfigurer}'s pattern, so
 * {@code EmbeddedApiKeyAuthenticationConverter} has already minted an {@code (environment, externalUserId, tenantId)}
 * principal by the time a request reaches here. {@link SecurityUtils#checkCurrentUserLogin(String)} stops a caller
 * addressing another customer's identity, and the environment is read off that same authenticated principal via
 * {@link PrincipalEnvironment} -- never from a request header or {@code EnvironmentContext} -- exactly as
 * {@code ConnectedUserResourceMembershipResolver} does for the same {@code EmbeddedApiKeyAuthenticationToken}.
 *
 * <p>
 * An unknown or disabled connected user is rejected with 403 and, per the trap
 * {@code ConnectedUserConstants.FRONTEND_RESERVED_PATH_SEGMENTS} guards against, never auto-created:
 * {@link ConnectedUserService#fetchConnectedUser(String, long)} is used, never {@code getConnectedUser} (which throws)
 * and never {@code createConnectedUser}.
 *
 * <p>
 * Two request mappings share this path because one operation has two response shapes. Spring compares the
 * {@code consumes} condition before {@code produces}, and a non-empty {@code consumes} unconditionally outranks an
 * empty one -- so the JSON mapping restricts {@code produces} to {@code application/json} rather than also advertising
 * {@code text/event-stream}, keeping the two mappings' {@code produces} conditions disjoint. Without that, every
 * {@code Content-Type: application/json} request would win the JSON mapping regardless of {@code Accept}, leaving the
 * SSE mapping unreachable -- exactly the bug the automation gateway's own controller shipped once.
 *
 * <p>
 * The wire models are generated from this module's own {@code openapi.yaml}, models only -- the API interface is
 * deliberately not generated, because OpenAPI allows one operation per path and method and a generated interface
 * therefore folds this endpoint's two mappings into a single method advertising both response media types, which is
 * exactly the SSE-unreachable shape described below. The spec's header carries that reasoning in full. Generating its
 * own models rather than importing {@code automation-ai-gateway-public-rest}'s keeps two independent public surfaces
 * from being coupled to one another, while still deriving both from a written contract.
 *
 * <p>
 * Binding the internal domain DTOs ({@link AiGatewayChatCompletionRequest} / {@link AiGatewayChatCompletionResponse})
 * directly to the wire was tried first and does not work: {@code stream} is a primitive {@code boolean} there, so
 * Jackson rejects any request omitting it (the common case); the domain types are also camelCase with no snake_case
 * mapping, so {@code max_tokens}, {@code top_p}, {@code routing_policy} and the nested message fields would silently
 * bind to {@code null}; and {@code tool_choice} -- either a bare string or an object on the wire -- cannot bind to
 * {@link AiGatewayToolChoice}'s exactly-one-of record at all. The response side has the same problem in reverse:
 * serializing {@link AiGatewayChatCompletionResponse} directly emits {@code finishReason} / {@code promptTokens} /
 * {@code completionTokens} / {@code totalTokens} instead of the wire's {@code finish_reason} / {@code prompt_tokens} /
 * {@code completion_tokens} / {@code total_tokens}, and leaks {@code gatewayMetadata} into the body --
 * {@link AiGatewayChatCompletionResponse.GatewayMetadata}'s own Javadoc says it is never serialized in the JSON body;
 * this controller emits it only as {@code x-gateway-*} response headers, via {@link #applyGatewayMetadataHeaders}.
 *
 * <p>
 * Unlike {@code AiGatewayChatCompletionApiController}, this controller passes {@code null} for both
 * {@code AiObservabilityTracingHeaders} and {@code AiPromptHeaders} on every facade call -- it does not read the
 * {@code x-trace-id} / {@code x-session-id} / {@code x-span-name} / {@code x-tags} tracing headers, nor the
 * prompt-registry headers, off the incoming request. This is an accepted gap for embedded callers today, not an
 * oversight: implementing it is deferred rather than folded into this endpoint.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@RestController
@RequestMapping("${openapi.openAPIDefinition.base-path.embedded:}/v1")
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class EmbeddedAiGatewayChatCompletionApiController {

    private static final String PATH_CHAT_COMPLETIONS = "/{externalUserId}/ai-gateway/chat/completions";

    private final AiGatewayFacade aiGatewayFacade;
    private final ConnectedUserService connectedUserService;

    @SuppressFBWarnings("EI")
    public EmbeddedAiGatewayChatCompletionApiController(
        AiGatewayFacade aiGatewayFacade, ConnectedUserService connectedUserService) {

        this.aiGatewayFacade = aiGatewayFacade;
        this.connectedUserService = connectedUserService;
    }

    @PostMapping(
        value = PATH_CHAT_COMPLETIONS, consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> chatCompletions(
        @PathVariable String externalUserId, @RequestBody ChatCompletionRequestModel payload) {

        SecurityUtils.checkCurrentUserLogin(externalUserId);

        Optional<Long> connectedUserId = resolveConnectedUserId(externalUserId);

        if (connectedUserId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .build();
        }

        AiGatewayChatCompletionRequest request = toDomainRequest(payload);

        if (request.stream()) {
            throw new IllegalArgumentException("Streaming requests must use Accept: text/event-stream header");
        }

        AiGatewayChatCompletionResponse response = aiGatewayFacade.chatCompletion(
            request, null, null, connectedUserId.get());

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();

        applyGatewayMetadataHeaders(builder, response);

        return builder.body(toResponseModel(response));
    }

    @PostMapping(value = PATH_CHAT_COMPLETIONS, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatCompletionsStream(
        @PathVariable String externalUserId, @RequestBody ChatCompletionRequestModel payload) {

        SecurityUtils.checkCurrentUserLogin(externalUserId);

        Optional<Long> connectedUserId = resolveConnectedUserId(externalUserId);

        if (connectedUserId.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Connected user not found or not enabled for external id: " + externalUserId);
        }

        // Identity and connected-user authorization are checked above, synchronously, so a rejection there produces a
        // real HTTP 403/other status rather than a 200 with an SSE error frame: ReactiveTypeHandler only subscribes to
        // this Flux after this method returns, so nothing has been written to the response while this method body
        // runs. Only the remaining, request-shaped failures -- an invalid payload from toDomainRequest, or a
        // mid-stream throw from the facade -- are deferred here, so they are converted into a reactor error signal
        // and framed as an SSE `event: error` by onErrorResume below, rather than escaping as a bare exception with
        // an abruptly closed stream. Mirrors AiGatewayChatCompletionApiController's reason for deferring those.
        return Flux.defer(() -> {
            AiGatewayChatCompletionRequest request = toDomainRequest(payload);

            return aiGatewayFacade.chatCompletionStream(request, null, null, connectedUserId.get(), null)
                .<ServerSentEvent<Object>>map(
                    domainResponse -> ServerSentEvent.<Object>builder()
                        .data(toResponseModel(domainResponse))
                        .build());
        })
            .onErrorResume(
                exception -> Flux.just(
                    ServerSentEvent.<Object>builder()
                        .event("error")
                        .data(
                            new StreamErrorChunkModel()
                                .type(resolveErrorType(exception))
                                .message(
                                    exception.getMessage() != null ? exception.getMessage()
                                        : "Streaming failed"))
                        .build()));
    }

    private static String resolveErrorType(Throwable exception) {
        if (exception instanceof BudgetExceededException) {
            return "budget_exceeded";
        }

        return exception.getClass()
            .getSimpleName();
    }

    /**
     * The environment comes off the authenticated principal, never from a request header or thread-local: the
     * connected-user id resolved here must be authorised for the same environment the caller was authenticated into.
     * Empty when the principal carries no environment at all -- not an api-key caller, so not governed by this endpoint
     * -- which this method treats identically to an unresolvable connected user.
     */
    private Optional<Long> resolveConnectedUserId(String externalUserId) {
        return PrincipalEnvironment.fetchCurrentPrincipalEnvironmentId()
            .flatMap(environmentId -> connectedUserService.fetchConnectedUser(externalUserId, environmentId))
            .filter(ConnectedUser::isEnabled)
            .map(ConnectedUser::getId);
    }

    /**
     * Emits {@code x-gateway-*} response headers when the facade populated metadata, mirroring
     * {@code AiGatewayChatCompletionApiController}. Headers are skipped individually when their value is null. This is
     * the ONLY place {@link AiGatewayChatCompletionResponse.GatewayMetadata} is read -- it never reaches the response
     * body (see the class Javadoc).
     */
    private static void applyGatewayMetadataHeaders(
        ResponseEntity.BodyBuilder builder, AiGatewayChatCompletionResponse response) {

        AiGatewayChatCompletionResponse.GatewayMetadata metadata = response.gatewayMetadata();

        if (metadata == null) {
            return;
        }

        if (metadata.provider() != null) {
            builder.header("x-gateway-provider", metadata.provider());
        }

        if (metadata.model() != null) {
            builder.header("x-gateway-model", metadata.model());
        }

        if (metadata.latencyMs() != null) {
            builder.header("x-gateway-latency-ms", String.valueOf(metadata.latencyMs()));
        }

        if (metadata.cacheHit() != null) {
            builder.header("x-gateway-cache-hit", String.valueOf(metadata.cacheHit()));
        }

        if (metadata.routingPolicy() != null) {
            builder.header("x-gateway-routing-policy", metadata.routingPolicy());
        }

        if (metadata.requestId() != null) {
            builder.header("x-gateway-request-id", metadata.requestId());
        }

        if (metadata.budgetWarningRemainingUsd() != null) {
            builder.header(
                "x-gateway-budget-warning",
                metadata.budgetWarningRemainingUsd()
                    .toPlainString());
        }
    }

    // ---- request wire model -> internal domain request ----

    /**
     * The generated models initialise every collection field to an empty one, so a request that simply omits
     * {@code tools}, {@code tool_calls}, {@code content_blocks} or {@code tags} arrives here as an empty collection
     * rather than null. The domain request distinguishes the two, so each is normalised back to null — which also folds
     * an explicitly-sent {@code []} into the same "not supplied" case, the reading those fields already had.
     */
    private static <T> List<T> nullIfEmpty(List<T> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    private static AiGatewayChatCompletionRequest toDomainRequest(ChatCompletionRequestModel payload) {
        if (payload.getModel() == null || payload.getModel()
            .isBlank()) {

            throw new IllegalArgumentException("'model' field is required");
        }

        if (payload.getMessages() == null || payload.getMessages()
            .isEmpty()) {

            throw new IllegalArgumentException("'messages' field is required and must not be empty");
        }

        List<AiGatewayChatMessage> messages = payload.getMessages()
            .stream()
            .map(EmbeddedAiGatewayChatCompletionApiController::toDomainMessage)
            .toList();

        List<ToolModel> toolModels = nullIfEmpty(payload.getTools());

        List<AiGatewayTool> tools = toolModels == null
            ? null
            : toolModels.stream()
                .map(EmbeddedAiGatewayChatCompletionApiController::toDomainTool)
                .toList();

        Map<String, String> tags = payload.getTags();

        return new AiGatewayChatCompletionRequest(
            payload.getModel(), messages, payload.getTemperature(), payload.getMaxTokens(), payload.getTopP(),
            Boolean.TRUE.equals(payload.getStream()), payload.getRoutingPolicy(), payload.getCache(),
            toToolChoice(payload.getToolChoice()), tools, tags == null || tags.isEmpty() ? null : tags);
    }

    private static AiGatewayChatMessage toDomainMessage(ChatMessageModel messageModel) {
        List<ToolCallModel> toolCallModels = nullIfEmpty(messageModel.getToolCalls());

        List<AiGatewayChatMessage.ToolCall> toolCalls = toolCallModels == null
            ? null
            : toolCallModels.stream()
                .map(EmbeddedAiGatewayChatCompletionApiController::toDomainToolCall)
                .toList();

        List<ContentBlockModel> contentBlockModels = nullIfEmpty(messageModel.getContentBlocks());

        List<AiGatewayContentBlock> contentBlocks = contentBlockModels == null
            ? null
            : contentBlockModels.stream()
                .map(EmbeddedAiGatewayChatCompletionApiController::toDomainContentBlock)
                .toList();

        return new AiGatewayChatMessage(
            AiGatewayChatRole.fromValue(messageModel.getRole()), messageModel.getContent(), contentBlocks, toolCalls,
            messageModel.getToolCallId());
    }

    private static AiGatewayContentBlock toDomainContentBlock(ContentBlockModel blockModel) {
        ContentBlockImageUrlModel imageUrlModel = blockModel.getImageUrl();

        AiGatewayContentBlock.ImageUrl imageUrl = imageUrlModel == null
            ? null
            : new AiGatewayContentBlock.ImageUrl(imageUrlModel.getUrl(), imageUrlModel.getDetail());

        return new AiGatewayContentBlock(
            AiGatewayContentBlockType.fromValue(blockModel.getType()), blockModel.getText(), imageUrl, null);
    }

    private static AiGatewayChatMessage.ToolCall toDomainToolCall(ToolCallModel toolCallModel) {
        ToolCallFunctionModel functionModel = toolCallModel.getFunction();

        return new AiGatewayChatMessage.ToolCall(
            toolCallModel.getId(), toolCallModel.getType(),
            functionModel == null
                ? null
                : new AiGatewayChatMessage.ToolCallFunction(functionModel.getName(), functionModel.getArguments()));
    }

    /**
     * {@code parameters} is a free-form JSON Schema object, so the spec types it {@code object} and the generator
     * renders it as a raw {@code Object}. Anything but a JSON object there is a malformed tool definition and is
     * rejected rather than silently dropped.
     */
    private static AiGatewayTool toDomainTool(ToolModel toolModel) {
        ToolFunctionModel functionModel = toolModel.getFunction();

        if (functionModel == null) {
            return new AiGatewayTool(toolModel.getType(), null);
        }

        Object parameters = functionModel.getParameters();

        if (parameters != null && !(parameters instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Invalid tool parameters: expected a JSON object");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parameterMap = (Map<String, Object>) parameters;

        return new AiGatewayTool(
            toolModel.getType(),
            new AiGatewayTool.AiGatewayToolFunction(
                functionModel.getName(), functionModel.getDescription(), parameterMap));
    }

    /**
     * {@code tool_choice} is either a bare string ({@code "auto"}, {@code "none"}, {@code "required"}) or an object
     * naming a specific tool on the wire, so the spec types it {@code object} and the generated field is a raw
     * {@code Object} -- Jackson lands a {@code String} or a {@code Map} depending on what the client sent. A
     * {@code oneOf} would say this more precisely but generates an empty marker interface nothing can implement, so the
     * wire contract lives in the property's description instead. Mirrors
     * {@code AiGatewayChatCompletionApiController.toToolChoice} exactly.
     */
    private static AiGatewayToolChoice toToolChoice(Object toolChoice) {
        switch (toolChoice) {
            case null -> {
                return null;
            }
            case String stringValue -> {
                return AiGatewayToolChoice.ofString(stringValue);
            }
            case Map<?, ?> map -> {
                Object toolRefObject = map.get("function");

                if (toolRefObject instanceof Map<?, ?> toolRefMap) {
                    Object name = toolRefMap.get("name");

                    String toolName = name != null ? name.toString() : null;

                    return AiGatewayToolChoice.ofTool(toolName);
                }
            }
            default -> {
            }
        }

        throw new IllegalArgumentException(
            "Invalid tool_choice value: expected string or object with 'function' key");
    }

    // ---- internal domain response -> response wire model ----

    private static ChatCompletionResponseModel toResponseModel(AiGatewayChatCompletionResponse response) {
        ChatCompletionResponseModel responseModel = new ChatCompletionResponseModel()
            .id(response.id())
            ._object(response.object())
            .created(response.created())
            .model(response.model());

        if (response.choices() != null) {
            responseModel.setChoices(
                response.choices()
                    .stream()
                    .map(EmbeddedAiGatewayChatCompletionApiController::toChoiceModel)
                    .toList());
        }

        return responseModel.usage(toUsageModel(response.usage()));
    }

    private static ChoiceModel toChoiceModel(AiGatewayChatCompletionResponse.Choice choice) {
        return new ChoiceModel()
            .index(choice.index())
            .message(choice.message() == null ? null : toMessageModel(choice.message()))
            .finishReason(choice.finishReason());
    }

    private static ChatMessageModel toMessageModel(AiGatewayChatMessage message) {
        ChatMessageModel messageModel = new ChatMessageModel()
            .role(
                message.role()
                    .getValue())
            .content(message.content());

        if (message.toolCalls() != null) {
            messageModel.setToolCalls(
                message.toolCalls()
                    .stream()
                    .map(EmbeddedAiGatewayChatCompletionApiController::toToolCallModel)
                    .toList());
        }

        return messageModel;
    }

    private static ToolCallModel toToolCallModel(AiGatewayChatMessage.ToolCall toolCall) {
        AiGatewayChatMessage.ToolCallFunction function = toolCall.function();

        return new ToolCallModel()
            .id(toolCall.id())
            .type(toolCall.type())
            .function(
                function == null
                    ? null
                    : new ToolCallFunctionModel()
                        .name(function.name())
                        .arguments(function.arguments()));
    }

    private static UsageModel toUsageModel(AiGatewayChatCompletionResponse.Usage usage) {
        if (usage == null) {
            return null;
        }

        // The domain counts are int; the generated setters take Long, and Java will not widen and box in one step.
        return new UsageModel()
            .promptTokens((long) usage.promptTokens())
            .completionTokens((long) usage.completionTokens())
            .totalTokens((long) usage.totalTokens());
    }
}
