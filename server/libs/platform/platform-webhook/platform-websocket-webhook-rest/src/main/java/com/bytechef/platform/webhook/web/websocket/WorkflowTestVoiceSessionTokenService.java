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

package com.bytechef.platform.webhook.web.websocket;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Mints and validates short-lived single-use tokens that authorize a browser to open a workflow-test voice WebSocket.
 *
 * <p>
 * The token is issued via {@code POST /internal/workflow-tests/{workflowId}/voice-session-token?environmentId=...},
 * which authorizes the caller to edit the workflow in that environment, and is consumed by the WS upgrade handler. The
 * environment is bound into the token at mint: the socket runs in the environment the caller was authorized for, never
 * in one it names later in the upgrade URL.
 *
 * @author Ivica Cardic
 */
@Service
public class WorkflowTestVoiceSessionTokenService {

    private static final long TOKEN_TTL_SECONDS = 60L;

    private final Cache<String, IssuedToken> issuedTokens = Caffeine.newBuilder()
        .expireAfterWrite(TOKEN_TTL_SECONDS, TimeUnit.SECONDS)
        .maximumSize(10000)
        .build();

    public Token issue(String workflowId, long environmentId) {
        String token = UUID.randomUUID()
            .toString();

        issuedTokens.put(token, new IssuedToken(workflowId, environmentId));

        return new Token(token, TOKEN_TTL_SECONDS);
    }

    /**
     * Consumes the token and verifies it was issued for {@code workflowId}.
     *
     * @return the environment the token was minted for, on the first call with a matching pair; empty for any later
     *         call, an unknown or expired token, or a token issued for another workflow
     */
    public OptionalLong consume(String token, String workflowId) {
        if (token == null || workflowId == null) {
            return OptionalLong.empty();
        }

        IssuedToken issuedToken = issuedTokens.getIfPresent(token);

        if (issuedToken == null || !issuedToken.workflowId()
            .equals(workflowId)) {

            return OptionalLong.empty();
        }

        issuedTokens.invalidate(token);

        return OptionalLong.of(issuedToken.environmentId());
    }

    public record Token(String token, long expiresInSeconds) {
    }

    private record IssuedToken(String workflowId, long environmentId) {
    }
}
