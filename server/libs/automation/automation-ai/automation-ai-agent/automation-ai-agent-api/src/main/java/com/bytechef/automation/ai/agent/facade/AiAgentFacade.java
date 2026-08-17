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

package com.bytechef.automation.ai.agent.facade;

import com.bytechef.automation.ai.agent.channel.ResolvedAgentChannel;
import com.bytechef.automation.ai.agent.domain.AiAgentChannel;
import com.bytechef.automation.ai.agent.domain.AiAgentElement;
import com.bytechef.automation.ai.agent.dto.AiAgentDTO;
import com.bytechef.automation.ai.agent.dto.AiAgentDeploymentDTO;
import com.bytechef.automation.ai.agent.dto.AiAgentVersionDTO;
import com.bytechef.automation.ai.agent.dto.ChatAgentDTO;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Facade for agent operations that span multiple services: adding the agent's generated workflow to its project,
 * keeping that draft workflow in sync with every channel/element/instructions edit, and enforcing the cross-entity
 * delete rules (deployed or sub-agent-referenced agents cannot be deleted; the permanent
 * {@code chat}/{@code workflowCall} channels cannot be removed).
 *
 * <p>
 * Every {@code workspaceId} parameter here is primitive {@code long} and must stay that way: the implementation gates
 * each of these methods on {@code hasPermission(#workspaceId, 'Workspace', ...)}, and a boxed {@code null} would reach
 * {@code AutomationPermissionEvaluator} as a null target id. That currently fails closed in both editions, but only
 * incidentally — the resolver cannot map {@code null} to an owner — so the parameter type, not the evaluator, is what
 * this relies on.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface AiAgentFacade {

    /**
     * Creates an agent in {@code workspaceId}. With a {@code projectId} the agent's generated workflow is added to that
     * project, which must belong to {@code workspaceId} and be visible to the caller; without one a new project named
     * after the agent is created for it.
     */
    AiAgentDTO createAgent(String title, String description, long workspaceId, @Nullable Long projectId);

    /**
     * Removes the agent and its own generated workflow, in every project version; the project and its other workflows
     * and agents stay. Refused while the agent's workflow is enabled in a deployment, or while another agent references
     * it as a sub-agent.
     */
    void deleteAgent(long id);

    /**
     * Removes every agent of {@code projectId}, ahead of the project's own deletion. The agents' generated workflows
     * are left to the project delete, which removes every workflow of the project anyway. Refused while an agent of
     * another project references one of them as a sub-agent.
     */
    void deleteProjectAgents(long projectId);

    AiAgentDTO getAgent(long id);

    List<AiAgentDTO> getAgents(long workspaceId);

    /**
     * Read-model for the per-agent channel section embedded in the Project Deployments page: every
     * {@code ProjectDeployment} of every agent's project in {@code workspaceId} that contains the agent's generated
     * workflow, across every {@link com.bytechef.platform.configuration.domain.Environment}. A project can hold several
     * deployments per environment, so each one is a row; deployments without the agent's workflow produce none. The
     * same lookup {@code AiAgentFacadeImpl.isEnabledInAnyDeployment} relies on.
     */
    List<AiAgentDeploymentDTO> getAgentDeployments(long workspaceId);

    /**
     * Every channel an agent can be reached through, resolved from the component registry — one entry per component-
     * declared {@code agentChannel(...)}, plus the synthesized {@code schedule} entry the registry cannot supply (a
     * schedule is not a channel; see {@code docs/superpowers/specs/2026-08-17-sdk-agent-channels-design.md}). Backs the
     * client's channel cards, which would otherwise have to mirror the registry by hand.
     */
    List<ResolvedAgentChannel> getAgentChannelDefinitions();

    /**
     * Read-model for the client's "Agent Chats" picker: every enabled, chat-reachable workflow of every agent in
     * {@code workspaceId} that has an enabled deployment in {@code environmentId}.
     *
     * <p>
     * Sibling of {@code workspaceChatWorkflows} (in {@code automation-configuration-graphql}), which lists chat
     * workflows by project rather than by agent. Rows carry a {@code workflowExecutionId} built exactly the way that
     * query builds it, so both feed the same client chat surface.
     * </p>
     *
     * <p>
     * Gated on workspace membership by the implementation, with the same scope the sibling uses. The two cascades sit
     * side by side in one launcher popup and disclose the same class of data — an entity name plus a workflow label —
     * so a caller who cannot see one has no business seeing the other. {@code workspaceId} is primitive precisely
     * because the gate keys on it: a {@code null} would reach the evaluator as a null target id.
     * </p>
     */
    List<ChatAgentDTO> getWorkspaceChatAgents(long workspaceId, long environmentId);

    /**
     * Partial update: a {@code null} {@code title}/{@code description}/{@code instructions} leaves that field's
     * existing value unchanged (same semantics as {@code updateAgentChannel}/{@code updateAgentElement}'s
     * {@code parameters}/{@code connectionId}) — there is no way to explicitly clear {@code description} or
     * {@code instructions} through this method today. {@code AgentInstructionsCard.tsx} is the only client caller and
     * sends only {@code instructions} on every save, so a "null clears" semantics here would silently wipe
     * {@code description} on every instructions edit.
     */
    AiAgentDTO updateAgent(long id, String title, String description, String instructions);

    /**
     * Replaces the agent's ENTIRE {@code settings} map (not a per-key merge) and regenerates the draft workflow —
     * built-in-tool emission reads {@code settings} on every generation, so a settings change is a draft-affecting
     * mutation like any other. Whole-map replace was chosen over merge for simplicity: the settings shape is small and
     * entirely client-owned (the Agent Settings UI always sends the complete object back), so there is no partial-field
     * editing use case a merge would serve. A {@code null} or empty {@code settings} resets every built-in tool to its
     * documented default (see {@code com.bytechef.automation.ai.agent.util.AiAgentSettings}) — there is no separate
     * "unset" state to preserve.
     */
    void updateAgentSettings(long id, Map<String, Object> settings);

    AiAgentChannel addAgentChannel(long agentId, String channelType, Map<String, Object> parameters, Long connectionId);

    /**
     * Partial update: a {@code null} {@code parameters} or {@code connectionId} leaves the existing value unchanged —
     * neither argument can be used to explicitly clear a previously-set value. There is currently no way to clear a
     * wired {@code connectionId} through this method; do a {@link #deleteAgentChannel(long)} followed by
     * {@link #addAgentChannel(long, String, Map, Long)} with {@code connectionId = null} instead.
     */
    void updateAgentChannel(long channelId, Map<String, Object> parameters, Long connectionId);

    void deleteAgentChannel(long channelId);

    AiAgentElement addAgentElement(
        long agentId, String kind, Long referenceId, Map<String, Object> parameters, Long connectionId);

    /**
     * Partial update: a {@code null} {@code parameters} or {@code connectionId} leaves the existing value unchanged —
     * see {@link #updateAgentChannel(long, Map, Long)}'s javadoc for the same caveat on clearing a
     * {@code connectionId}.
     */
    void updateAgentElement(long elementId, Map<String, Object> parameters, Long connectionId);

    void deleteAgentElement(long elementId);

    /**
     * Validates every agent of {@code projectId} and regenerates its draft workflow, so that the project version about
     * to be published snapshots the agents' current configuration. Throws when an agent is not publishable, which
     * aborts the project publish.
     */
    void prepareProjectPublish(long projectId);

    /**
     * The agent's version history, newest first — every {@code ProjectVersion} of its backing project, draft included.
     */
    List<AiAgentVersionDTO> getAgentVersions(long id);

    /**
     * The agent's configuration as a JSON document: title, description, instructions, settings, channels and elements.
     * <p>
     * Deliberately NOT a full backup. Connection ids are omitted — a connection belongs to a workspace and an
     * environment, so carrying one across would either dangle or point at someone else's credential. The generated
     * workflow, deployments and version history are omitted too: they are derived from this configuration, or from the
     * target instance's own state.
     */
    String exportAgent(long id);

    /**
     * Creates an agent in {@code workspaceId} from {@link #exportAgent(long)}'s JSON, inside {@code projectId} or a new
     * project exactly as {@link #createAgent} does.
     * <p>
     * Elements that reference another row by id — {@code SKILL}, {@code SUB_AGENT}, {@code KNOWLEDGE_BASE} — are
     * skipped: those ids mean nothing in the target workspace, and importing them would generate a workflow pointing at
     * rows that do not exist. Channels arrive without their connections, so an imported agent needs re-wiring before it
     * can publish.
     */
    AiAgentDTO importAgent(long workspaceId, String json, @Nullable Long projectId);

    /**
     * Copies every agent of {@code sourceProjectId} into {@code targetProjectId}, both in {@code workspaceId}. Unlike
     * an export/import round trip nothing is dropped: the copy keeps the source's instructions, settings, channels and
     * every element — skills, sub-agents, knowledge bases and chat memory included, since their ids stay valid in the
     * same workspace — with the channels' and elements' connections. A sub-agent reference to another agent of the
     * source project is pointed at that agent's copy. Each copy gets a unique slug name and its own freshly generated
     * workflow in the target project; the source agents' generated workflows are not copied.
     */
    List<AiAgentDTO> copyProjectAgents(long sourceProjectId, long targetProjectId, long workspaceId);

    /**
     * Replaces the configuration of the existing agent {@code id} with {@link #exportAgent(long)}'s JSON: title,
     * description, instructions and settings are taken from the document, and channels and elements are matched to the
     * document by type and kind in position order — a matched row takes the document's parameters and keeps its own
     * connection, an unmatched document entry is added and an unmatched non-permanent row is removed. Elements that
     * reference another row by id ({@code SKILL}, {@code SUB_AGENT}, {@code KNOWLEDGE_BASE}) cannot be expressed in the
     * document and are left as they are.
     */
    AiAgentDTO updateAgentFromExport(long id, String json);

    /**
     * Resolves the workflow id backing the agent's current draft (i.e. {@code project.getLastProjectVersion()}) — the
     * workflow the client's test chat panel executes against. Distinct from any workflow id belonging to a previously
     * published version.
     */
    String getDraftWorkflowId(long agentId);
}
