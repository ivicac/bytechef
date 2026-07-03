/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.agent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * Diagnostic advisor that logs the model's token usage — including the provider's native prompt-cache counters — once
 * per LLM response, so prompt-caching effectiveness can be measured directly.
 *
 * <p>
 * It exists because the AG-UI agent streams every turn, and the streaming usage aggregator
 * ({@code MessageAggregator.DefaultUsage}) collapses usage into a 3-key map that drops the provider's prompt-cache
 * counters — so {@link org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor} reports misleading values (e.g.
 * {@code promptTokens=3} next to a {@code null cacheReadInputTokens}). To recover the truth, the streaming path here
 * does NOT aggregate: it taps the raw chunks and logs the model-level chunk usage, whose <em>native</em> usage is the
 * provider's own object — for Anthropic the SDK usage carrying {@code cache_read_input_tokens} /
 * {@code cache_creation_input_tokens}, the ground truth for whether the cached prefix (system prompt + tool
 * definitions) is actually being read across turns. If no model-level usage survives to the advisor, that fact is
 * itself logged so the measurement gap is visible rather than silent.
 * </p>
 *
 * <p>
 * The native usage class name is logged alongside the value so its exact shape is discoverable on the first capture
 * without guessing accessor names — read as Object to avoid coupling ai-hub-service to any provider SDK type. Output is
 * gated on this class's logger at DEBUG; the default INFO root level means zero overhead in production. Enable with
 * {@code logging.level.com.bytechef.ee.ai.hub.agent.AiHubModelUsageLoggingAdvisor=DEBUG}.
 * </p>
 *
 * <p>
 * Registered for every AI Hub agent (ASK + BUILD) in {@link AiHubSpringAIAgent.Builder#build()}. Because the base
 * {@code SpringAIAgent} attaches advisors to the per-request spec rather than baking them into a single ChatClient,
 * this advisor also observes turns served by a per-request override ChatClient (user-selected or personal-agent model),
 * not only the workspace-default client.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class AiHubModelUsageLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AiHubModelUsageLoggingAdvisor.class);

    /**
     * Innermost so it observes the actual model response usage after every other request rewrite has settled. Ties with
     * {@code SimpleLoggerAdvisor} (also {@code Integer.MAX_VALUE}) are harmless — both are read-only observers.
     */
    private static final int ORDER = Integer.MAX_VALUE;

    @Override
    public String getName() {
        return "ai-hub-model-usage-logging";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);

        logUsage("call", extractUsage(chatClientResponse));

        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
        ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {

        // Do NOT aggregate the stream here. The streaming aggregator (MessageAggregator.DefaultUsage) collapses usage
        // into a 3-key map that drops the provider's prompt-cache counters (cache_read / cache_creation), making
        // caching unobservable. Instead, tap the raw chunks: the model-level chunk usage still carries the provider's
        // native usage object (a non-Map), which is the ground truth for cache effectiveness. Log it when it appears.
        AtomicBoolean loggedModelUsage = new AtomicBoolean(false);

        return streamAdvisorChain.nextStream(chatClientRequest)
            .doOnNext(chatClientResponse -> {
                if (!log.isDebugEnabled()) {
                    return;
                }

                Usage usage = extractUsage(chatClientResponse);

                if (usage != null && !(usage.getNativeUsage() instanceof Map)) {
                    logUsage("stream", usage);

                    loggedModelUsage.set(true);
                }
            })
            .doOnComplete(() -> {
                if (log.isDebugEnabled() && !loggedModelUsage.get()) {
                    log.debug(
                        "AI Hub model usage (stream) — no model-level usage reached the advisor; the stream "
                            + "aggregator collapsed it to a cache-less map, so prompt-cache effectiveness is not "
                            + "observable at this layer.");
                }
            });
    }

    private static Usage extractUsage(ChatClientResponse chatClientResponse) {
        if (chatClientResponse == null) {
            return null;
        }

        ChatResponse chatResponse = chatClientResponse.chatResponse();

        if (chatResponse == null) {
            return null;
        }

        return chatResponse.getMetadata()
            .getUsage();
    }

    private void logUsage(String phase, Usage usage) {
        if (!log.isDebugEnabled() || usage == null) {
            return;
        }

        Object nativeUsage = usage.getNativeUsage();

        log.debug(
            "AI Hub model usage ({}) — promptTokens={}, completionTokens={}, totalTokens={}, usageClass={}, "
                + "nativeUsageClass={}, nativeUsage={}",
            phase, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
            usage.getClass()
                .getName(),
            nativeUsage == null ? "null" : nativeUsage.getClass()
                .getName(),
            nativeUsage);
    }
}
