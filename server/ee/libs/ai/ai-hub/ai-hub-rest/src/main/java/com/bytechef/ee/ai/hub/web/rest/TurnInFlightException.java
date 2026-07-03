/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.rest;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@link AiHubApiController#chat} when a turn is already in flight for the resolved thread — one turn at a
 * time per chat, so a second sender (the owner or a participant, whichever one is not already running) gets an explicit
 * conflict instead of silently piggybacking on the running turn's stream.
 *
 * <p>
 * {@code @ResponseStatus} names the HTTP status for any path that does not go through the controller's own
 * {@code @ExceptionHandler}; the handler in {@link AiHubApiController} is what actually shapes the response body.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class TurnInFlightException extends RuntimeException {

    private final RunningUser runningUser;

    public TurnInFlightException(RunningUser runningUser) {
        super("A turn is already in flight for this chat");

        this.runningUser = runningUser;
    }

    public RunningUser getRunningUser() {
        return runningUser;
    }

    /**
     * The user whose turn is currently running, resolved from the chat's most recently recorded {@code AiHubChatTurn}.
     * Both fields are {@code null} when the running turn's sender cannot be resolved (a benign race with a concurrent
     * delete, or a login that no longer resolves).
     */
    public record RunningUser(@Nullable Long userId, @Nullable String userName) {
    }
}
