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

package com.bytechef.automation.ai.a2a.server.web.rest;

import com.bytechef.automation.ai.a2a.server.facade.AutomationA2AServerFacade;
import com.bytechef.platform.ai.a2a.A2AAgentCardFactory;
import com.bytechef.platform.ai.a2a.A2AAgentDescriptor;
import com.bytechef.platform.ai.a2a.A2AProtocolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.a2a.spec.AgentCard;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.MessageSendParams;
import io.a2a.util.Utils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes ByteChef agent-backed workflows over the A2A (Agent2Agent) protocol.
 *
 * <p>
 * Two endpoints per A2A server secret key:
 * <ul>
 * <li>{@code GET /api/automation/a2a/{secretKey}/.well-known/agent-card.json} — the discovery agent card.</li>
 * <li>{@code POST /api/automation/a2a/{secretKey}} — the JSON-RPC surface; only {@code message/send} is handled.</li>
 * </ul>
 * Both are authenticated by the secret-key API-key stack (see the A2A security configurer). CSRF is intentionally
 * disabled for these endpoints — external A2A clients cannot present CSRF tokens; security is the API key.
 * </p>
 *
 * @author Ivica Cardic
 */
@RestController
@RequestMapping("/api/automation/a2a")
class A2AServerController {

    private static final Logger log = LoggerFactory.getLogger(A2AServerController.class);

    private final A2AAgentCardFactory agentCardFactory;
    private final A2AProtocolHandler protocolHandler;
    private final AutomationA2AServerFacade automationA2AServerFacade;
    private final String publicUrl;

    @SuppressFBWarnings("EI")
    A2AServerController(
        A2AAgentCardFactory agentCardFactory, A2AProtocolHandler protocolHandler,
        AutomationA2AServerFacade automationA2AServerFacade, @Value("${bytechef.webhook.url:}") String publicUrl) {

        this.agentCardFactory = agentCardFactory;
        this.protocolHandler = protocolHandler;
        this.automationA2AServerFacade = automationA2AServerFacade;
        this.publicUrl = publicUrl;
    }

    @GetMapping(value = "/{secretKey}/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> getAgentCard(@PathVariable String secretKey, HttpServletRequest request) throws Exception {
        A2AAgentDescriptor descriptor = automationA2AServerFacade.getAgentDescriptor(
            secretKey, resolveEndpointUrl(secretKey, request));

        AgentCard agentCard = agentCardFactory.create(descriptor);

        return ResponseEntity.ok(Utils.OBJECT_MAPPER.writeValueAsString(agentCard));
    }

    @PostMapping(
        value = "/{secretKey}", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> handleJsonRpc(@PathVariable String secretKey, @RequestBody String body) throws Exception {
        JsonNode root = Utils.OBJECT_MAPPER.readTree(body);

        Object requestId = extractId(root);
        String method = root.hasNonNull("method") ? root.get("method")
            .asText() : null;

        MessageSendParams params = null;

        if (A2AProtocolHandler.METHOD_SEND_MESSAGE.equals(method) && root.hasNonNull("params")) {
            params = Utils.OBJECT_MAPPER.treeToValue(root.get("params"), MessageSendParams.class);
        }

        JSONRPCResponse<?> response = protocolHandler.handle(secretKey, requestId, method, params);

        return ResponseEntity.ok(Utils.OBJECT_MAPPER.writeValueAsString(response));
    }

    private static Object extractId(JsonNode root) {
        JsonNode idNode = root.get("id");

        if (idNode == null || idNode.isNull()) {
            return null;
        }

        if (idNode.isNumber()) {
            return idNode.numberValue();
        }

        return idNode.asText();
    }

    private String resolveEndpointUrl(String secretKey, HttpServletRequest request) {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl + "/api/automation/a2a/" + secretKey;
        }

        String requestUrl = request.getRequestURL()
            .toString();

        return requestUrl.replace("/.well-known/agent-card.json", "");
    }
}
