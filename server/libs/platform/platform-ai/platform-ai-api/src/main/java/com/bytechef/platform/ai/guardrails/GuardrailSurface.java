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

package com.bytechef.platform.ai.guardrails;

import java.util.Set;

/**
 * The complete set of surfaces on which guardrails are applied. A surface is a place where user or workspace data
 * reaches a model; its name is the {@code surface} argument every guardrails entry point takes, and the tag every
 * metric carries.
 *
 * <p>
 * These were four scattered definitions before this registry existed: {@link McpOutboundRedactorProvider}'s two public
 * constants, a private one in {@code CopilotGuardrailsAdvisorFactory}, and {@code "ai_agent"} written as a bare literal
 * at two call sites. A misspelled literal would have produced a surface that silently resolved its own policy and
 * reported its own metrics under a name nothing else used — visible only as a metric that never moved.
 * </p>
 *
 * <p>
 * {@code GuardrailSurfaceCoverageTest} pins two things against this registry: that every surface string reaching a
 * guardrails entry point is one of these constants rather than a literal, and that every production site calling a
 * model directly — bypassing the {@code ChatClient} advisor chain guardrails attach to — is named in that test's
 * exemption list with a reason. The second is the one that matters: an advisor cannot guard a call that never passes
 * through an advisor chain, so such a site is unguardable by construction and has to be recognised as a deliberate
 * choice rather than discovered later.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class GuardrailSurface {

    /**
     * The canvas AI Agent component, running inside a workflow. Its workspace is derived from the run's
     * {@code jobPrincipalId}, never supplied by a caller.
     */
    public static final String AI_AGENT = "ai_agent";

    /**
     * Every Copilot {@code *SpringAIAgent} and {@code ChatClient} site behind {@code CopilotGuardrailsAdvisorFactory},
     * including the AI Hub delegation sub-agents that resolve through it.
     */
    public static final String COPILOT = "copilot";

    /**
     * AI Hub chat-title generation. The title is produced from the user's own first messages, so it carries exactly the
     * content the chat turn itself is guarded for; before this surface existed the turn was redacted and the title
     * generated from it was not.
     */
    public static final String AI_HUB = "ai_hub";

    /**
     * API connector generation from documentation. The prompt carries the free-text instructions an admin typed
     * alongside the scraped documentation. API connectors are tenant-level and admin-only — there is no workspace to
     * resolve, so this surface always resolves the tenant-default settings row, the same way {@link #MCP_EMBEDDED}
     * does. That is a property of the domain, not a fallback.
     */
    public static final String API_CONNECTOR = "api_connector";

    /**
     * LLM-as-judge evaluation of an observability trace. This surface carries the heaviest content of any: the prompt
     * replays a trace's own input and output plus its retrieval spans, so it re-sends to a judge model whatever the
     * evaluated call already sent. Its workspace comes from the loaded trace row's {@code workspace_id}, never from a
     * caller — and null there is the trace's real "no workspace" state, not a missing value.
     */
    public static final String AI_EVAL = "ai_eval";

    /**
     * Outbound redaction on the automation MCP server — tool results leaving for a model the tenant did not choose.
     */
    public static final String MCP_AUTOMATION = McpOutboundRedactorProvider.SURFACE_AUTOMATION;

    /**
     * Outbound redaction on the embedded MCP server. Embedded has no workspace, so it resolves the {@code EMBEDDED}
     * settings row rather than a workspace one.
     */
    public static final String MCP_EMBEDDED = McpOutboundRedactorProvider.SURFACE_EMBEDDED;

    public static final Set<String> ALL =
        Set.of(AI_AGENT, AI_EVAL, AI_HUB, API_CONNECTOR, COPILOT, MCP_AUTOMATION, MCP_EMBEDDED);

    private GuardrailSurface() {
    }
}
