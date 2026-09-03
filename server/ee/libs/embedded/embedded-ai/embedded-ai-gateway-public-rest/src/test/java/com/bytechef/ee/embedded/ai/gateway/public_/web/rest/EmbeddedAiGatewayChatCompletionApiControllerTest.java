/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.ai.gateway.public_.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.ee.automation.ai.gateway.facade.AiGatewayFacade;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionResponse;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.security.web.authentication.AbstractApiKeyAuthenticationToken;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import reactor.core.publisher.Flux;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class EmbeddedAiGatewayChatCompletionApiControllerTest {

    private static final long ENVIRONMENT_ID = 1L;
    private static final String REQUEST_BODY = """
        {"model": "gpt-4o", "messages": [{"role": "user", "content": "hi"}]}""";

    private AiGatewayFacade aiGatewayFacade;
    private ConnectedUser connectedUser;
    private ConnectedUserService connectedUserService;
    private MockMvc mockMvc;

    @BeforeEach
    void beforeEach() {
        aiGatewayFacade = mock(AiGatewayFacade.class);
        connectedUser = mock(ConnectedUser.class);
        connectedUserService = mock(ConnectedUserService.class);

        when(connectedUser.getId()).thenReturn(5L);
        when(connectedUser.isEnabled()).thenReturn(true);

        when(aiGatewayFacade.chatCompletion(any(), any(), any(), anyLong())).thenReturn(chatCompletionResponse());
        when(aiGatewayFacade.chatCompletionStream(any(), any(), any(), any(), any()))
            .thenReturn(Flux.just(chatCompletionResponse()));

        mockMvc = MockMvcBuilders.standaloneSetup(
            new EmbeddedAiGatewayChatCompletionApiController(aiGatewayFacade, connectedUserService))
            .build();
    }

    @AfterEach
    void afterEach() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testResolvesTheConnectedUserAndPassesItsIdToTheFacade() throws Exception {
        authenticateAs("customer-1");

        when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
            .thenReturn(Optional.of(connectedUser));

        mockMvc.perform(
            post("/v1/customer-1/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
            .andExpect(status().isOk());

        verify(aiGatewayFacade).chatCompletion(any(), any(), any(), eq(5L));
    }

    @Test
    void testUnknownExternalUserIdIsRejected() throws Exception {
        authenticateAs("ghost");

        when(connectedUserService.fetchConnectedUser("ghost", ENVIRONMENT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(
            post("/v1/ghost/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
            .andExpect(status().isForbidden());

        verifyNoInteractions(aiGatewayFacade);
    }

    @Test
    void testDisabledConnectedUserIsRejected() throws Exception {
        authenticateAs("customer-1");

        when(connectedUser.isEnabled()).thenReturn(false);
        when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
            .thenReturn(Optional.of(connectedUser));

        mockMvc.perform(
            post("/v1/customer-1/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    void testNoConnectedUserIsEverCreated() throws Exception {
        authenticateAs("ghost");

        when(connectedUserService.fetchConnectedUser("ghost", ENVIRONMENT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(
            post("/v1/ghost/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY));

        verify(connectedUserService, never()).createConnectedUser(anyString(), anyLong());
        verify(connectedUserService, never()).createConnectedUser(anyString(), any(Environment.class));
    }

    /**
     * The guarantee this whole task exists to provide: a caller authenticated as one connected user cannot address
     * another by naming a different {@code externalUserId} in the path. Deleting the
     * {@code SecurityUtils.checkCurrentUserLogin} line from the JSON mapping's {@code chatCompletions} method still
     * passes all the other tests, since every one of them authenticates as the same externalUserId it posts to -- this
     * is the only test that would catch that deletion on the JSON mapping. The streaming mapping has its own
     * independent call site, guarded separately by {@link #testMismatchedIdentityIsRejectedOnStreamingMapping}.
     *
     * <p>
     * {@code standaloneSetup} runs no real Spring Security filter chain, so nothing here translates
     * {@link AccessDeniedException} into an HTTP response the way {@code ExceptionTranslationFilter} would in the real
     * app; it propagates out of {@code perform(...)} instead, which is still a positive assertion that rejection
     * happened before any gateway work.
     */
    @Test
    void testMismatchedIdentityIsRejected() {
        authenticateAs("someone-else");

        assertThatThrownBy(
            () -> mockMvc.perform(
                post("/v1/customer-1/ai-gateway/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(REQUEST_BODY)))
                        .hasCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(aiGatewayFacade);
        verifyNoInteractions(connectedUserService);
    }

    /**
     * Streaming counterpart of {@link #testMismatchedIdentityIsRejected}: guards
     * {@link EmbeddedAiGatewayChatCompletionApiController#chatCompletionsStream}'s OWN
     * {@code SecurityUtils.checkCurrentUserLogin} call site, hoisted above the {@code Flux.defer} specifically so a
     * mismatched identity is rejected before the SSE response starts rather than surfacing as a
     * {@code text/event-stream} {@code event: error} frame with a 200 status.
     */
    @Test
    void testMismatchedIdentityIsRejectedOnStreamingMapping() {
        authenticateAs("someone-else");

        assertThatThrownBy(
            () -> mockMvc.perform(
                post("/v1/customer-1/ai-gateway/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content(REQUEST_BODY)))
                        .hasCauseInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(aiGatewayFacade);
        verifyNoInteractions(connectedUserService);
    }

    /**
     * Streaming counterpart of {@link #testUnknownExternalUserIdIsRejected}: an unknown connected user must reject the
     * streaming mapping with a real 403, not a 200 with an SSE {@code event: error} frame. Both the identity check and
     * the connected-user resolution are hoisted above {@code Flux.defer} in
     * {@link EmbeddedAiGatewayChatCompletionApiController#chatCompletionsStream} precisely so this is reachable.
     */
    @Test
    void testUnknownConnectedUserIsRejectedOnStreamingMapping() throws Exception {
        authenticateAs("ghost");

        when(connectedUserService.fetchConnectedUser("ghost", ENVIRONMENT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(
            post("/v1/ghost/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(REQUEST_BODY))
            .andExpect(status().isForbidden());

        verifyNoInteractions(aiGatewayFacade);
    }

    /**
     * Guards the exact defect flagged in review: binding the request body straight to the internal domain
     * {@link AiGatewayChatCompletionRequest} silently drops {@code max_tokens}, {@code top_p} and
     * {@code routing_policy} to null, because that record has no snake_case mapping. A fixture using only
     * {@code model}/{@code messages}/{@code role}/{@code content} -- whose camelCase and snake_case spellings coincide
     * -- cannot see this; this one deliberately exercises every field whose wire name differs from its Java name.
     */
    @Test
    void testRequestBindsSnakeCaseFieldsWithStringToolChoice() throws Exception {
        authenticateAs("customer-1");

        when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
            .thenReturn(Optional.of(connectedUser));

        String requestBody = """
            {
                "model": "gpt-4o",
                "messages": [{"role": "user", "content": "hi"}],
                "max_tokens": 256,
                "top_p": 0.5,
                "routing_policy": "cost-optimized",
                "tool_choice": "auto"
            }""";

        mockMvc.perform(
            post("/v1/customer-1/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        AiGatewayChatCompletionRequest request = captureFacadeRequest();

        assertThat(request.maxTokens()).isEqualTo(256);
        assertThat(request.topP()).isEqualTo(0.5);
        assertThat(request.routingPolicy()).isEqualTo("cost-optimized");
        assertThat(request.toolChoice()
            .isStringValue()).isTrue();
        assertThat(request.toolChoice()
            .stringValue()).isEqualTo("auto");
    }

    /**
     * The hazard of generating the wire models rather than hand-writing them: a generated model initialises every
     * collection field to an empty one, so a request that simply omits {@code tools}, {@code tags}, {@code tool_calls}
     * or {@code content_blocks} would reach the facade carrying empty collections where the hand-written records passed
     * null. The domain request distinguishes the two, so the controller normalises them back. Nothing else in this
     * suite sends a request without those fields, which is exactly why this test exists.
     */
    @Test
    void testOmittedCollectionFieldsReachTheFacadeAsNullNotEmpty() throws Exception {
        authenticateAs("customer-1");

        when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
            .thenReturn(Optional.of(connectedUser));

        String requestBody = """
            {
                "model": "gpt-4o",
                "messages": [{"role": "user", "content": "hi"}]
            }""";

        mockMvc.perform(
            post("/v1/customer-1/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        AiGatewayChatCompletionRequest request = captureFacadeRequest();

        assertThat(request.tools()).isNull();
        assertThat(request.tags()).isNull();
        assertThat(request.messages()).hasSize(1);

        AiGatewayChatMessage message = request.messages()
            .getFirst();

        assertThat(message.toolCalls()).isNull();
        assertThat(message.contentBlocks()).isNull();
    }

    /**
     * The object shape of {@code tool_choice}. {@link com.bytechef.ee.platform.ai.gateway.dto.AiGatewayToolChoice} is a
     * record whose canonical constructor enforces exactly-one-of {@code stringValue}/{@code toolRef}; binding this
     * shape straight to that record (rather than through the raw-{@code Object} wire field plus manual conversion)
     * cannot work at all, since the wire object carries neither component name.
     */
    @Test
    void testRequestBindsSnakeCaseFieldsWithObjectToolChoice() throws Exception {
        authenticateAs("customer-1");

        when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
            .thenReturn(Optional.of(connectedUser));

        String requestBody = """
            {
                "model": "gpt-4o",
                "messages": [{"role": "user", "content": "hi"}],
                "max_tokens": 256,
                "top_p": 0.5,
                "routing_policy": "cost-optimized",
                "tool_choice": {"type": "function", "function": {"name": "lookup"}}
            }""";

        mockMvc.perform(
            post("/v1/customer-1/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        AiGatewayChatCompletionRequest request = captureFacadeRequest();

        assertThat(request.maxTokens()).isEqualTo(256);
        assertThat(request.topP()).isEqualTo(0.5);
        assertThat(request.routingPolicy()).isEqualTo("cost-optimized");
        assertThat(request.toolChoice()
            .isStringValue()).isFalse();
        assertThat(request.toolChoice()
            .toolRef()
            .name()).isEqualTo("lookup");
    }

    /**
     * Guards the other defect flagged in review: serializing the domain {@link AiGatewayChatCompletionResponse}
     * directly emits {@code finishReason} / {@code promptTokens} / {@code completionTokens} / {@code totalTokens}
     * instead of the wire's {@code finish_reason} / {@code prompt_tokens} / {@code completion_tokens} /
     * {@code total_tokens}, and leaks {@code gatewayMetadata} into the body even though its own Javadoc says it is
     * never serialized there. The response is built with non-null metadata specifically so this test would fail on the
     * old body-serialization approach rather than passing vacuously.
     */
    @Test
    void testResponseUsesWireFieldNamesAndOmitsGatewayMetadataFromTheBody() throws Exception {
        authenticateAs("customer-1");

        when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
            .thenReturn(Optional.of(connectedUser));
        when(aiGatewayFacade.chatCompletion(any(), any(), any(), anyLong()))
            .thenReturn(chatCompletionResponseWithMetadata());

        MvcResult mvcResult = mockMvc.perform(
            post("/v1/customer-1/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
            .andExpect(jsonPath("$.usage.prompt_tokens").value(3))
            .andExpect(jsonPath("$.usage.completion_tokens").value(7))
            .andExpect(jsonPath("$.usage.total_tokens").value(10))
            .andExpect(header().string("x-gateway-provider", "openai"))
            .andExpect(header().string("x-gateway-budget-warning", "2.50"))
            .andReturn();

        String responseBody = mvcResult.getResponse()
            .getContentAsString();

        assertThat(responseBody).doesNotContain("gatewayMetadata");
    }

    private AiGatewayChatCompletionRequest captureFacadeRequest() {
        ArgumentCaptor<AiGatewayChatCompletionRequest> requestCaptor = ArgumentCaptor.forClass(
            AiGatewayChatCompletionRequest.class);

        verify(aiGatewayFacade).chatCompletion(requestCaptor.capture(), any(), any(), eq(5L));

        return requestCaptor.getValue();
    }

    /**
     * Regression guard for the trap described on {@link EmbeddedAiGatewayChatCompletionApiController}: the JSON mapping
     * must not also advertise {@code text/event-stream} in {@code produces}, or it would unconditionally outrank the
     * SSE mapping (Spring compares {@code consumes} before {@code produces}) and leave the streaming handler
     * unreachable, exactly as shipped once on the automation gateway endpoint.
     *
     * <p>
     * Also guards that the streaming mapping passes the RESOLVED connected-user id, not a stray {@code null}, to the
     * facade: before this assertion existed the {@code beforeEach} stub matched
     * {@code chatCompletionStream(any(), any(), any(), any(), any())} for {@code connectedUserId} too, so a streaming
     * request that silently routed with a {@code null} connected user -- meaning no embedded default policy and no
     * spend attribution -- would have passed every test in this class.
     */
    @Test
    void testAcceptEventStreamDispatchesToStreamingHandler() throws Exception {
        authenticateAs("customer-1");

        when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
            .thenReturn(Optional.of(connectedUser));

        MvcResult mvcResult = mockMvc.perform(
            post("/v1/customer-1/ai-gateway/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(REQUEST_BODY))
            .andReturn();

        HandlerMethod handlerMethod = assertInstanceOf(HandlerMethod.class, mvcResult.getHandler());

        assertEquals(
            "chatCompletionsStream", handlerMethod.getMethod()
                .getName());

        verify(aiGatewayFacade).chatCompletionStream(any(), any(), any(), eq(5L), any());
    }

    /**
     * I3: a malformed request that trips {@code toDomainRequest}'s explicit guards must surface as a client error, not
     * an unguarded NPE reaching {@code AiGatewayExceptionHandler}'s generic 500 handler. Requires the connected user to
     * resolve successfully first, so the request reaches {@code toDomainRequest} at all.
     *
     * <p>
     * {@code standaloneSetup} registers only this controller, not {@code AiGatewayExceptionHandler} (a separate
     * module's {@code @RestControllerAdvice}), so the {@link IllegalArgumentException} propagates out of
     * {@code perform(...)} here rather than being translated to a 400 response the way it is in the real app --
     * mirroring how {@link #testMismatchedIdentityIsRejected} asserts the thrown {@link AccessDeniedException} rather
     * than a status for the same reason. {@code AiGatewayExceptionHandlerTest} pins the 400 mapping itself (see
     * {@code testIllegalArgumentExceptionReturnsBadRequest}), and its {@code @RestControllerAdvice} basePackages now
     * cover this controller's package.
     */
    @Test
    void testEmptyRequestBodyThrowsIllegalArgumentException() throws Exception {
        authenticateAs("customer-1");

        when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
            .thenReturn(Optional.of(connectedUser));

        assertThatThrownBy(
            () -> mockMvc.perform(
                post("/v1/customer-1/ai-gateway/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")))
                        .hasCauseInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(aiGatewayFacade);
    }

    private static void authenticateAs(String externalUserId) {
        User user = new User(externalUserId, "", List.of());

        SecurityContextHolder.getContext()
            .setAuthentication(new TestApiKeyAuthenticationToken(ENVIRONMENT_ID, user));
    }

    private static AiGatewayChatCompletionResponse chatCompletionResponse() {
        return new AiGatewayChatCompletionResponse(
            "chatcmpl-1", "chat.completion", 0L, "gpt-4o",
            List.of(new AiGatewayChatCompletionResponse.Choice(
                0, new AiGatewayChatMessage(AiGatewayChatRole.ASSISTANT, "hello"), "stop")),
            new AiGatewayChatCompletionResponse.Usage(1, 1, 2));
    }

    private static AiGatewayChatCompletionResponse chatCompletionResponseWithMetadata() {
        return new AiGatewayChatCompletionResponse(
            "chatcmpl-1", "chat.completion", 0L, "gpt-4o",
            List.of(new AiGatewayChatCompletionResponse.Choice(
                0, new AiGatewayChatMessage(AiGatewayChatRole.ASSISTANT, "hello"), "stop")),
            new AiGatewayChatCompletionResponse.Usage(3, 7, 10),
            new AiGatewayChatCompletionResponse.GatewayMetadata(
                "openai", "gpt-4o", 12L, false, "cost-optimized", "req-1", new BigDecimal("2.50")));
    }

    /**
     * The embedded request path authenticates as {@code EmbeddedApiKeyAuthenticationToken} (in
     * {@code embedded-security-web-impl}, not a dependency of this module). Any
     * {@link AbstractApiKeyAuthenticationToken} carries the environment identically --
     * {@link com.bytechef.platform.security.web.authentication.PrincipalEnvironment} reads only the base type -- so
     * this minimal test-local subclass is a faithful stand-in.
     */
    private static final class TestApiKeyAuthenticationToken extends AbstractApiKeyAuthenticationToken {

        TestApiKeyAuthenticationToken(long environmentId, User user) {
            super(environmentId, user);
        }
    }
}
