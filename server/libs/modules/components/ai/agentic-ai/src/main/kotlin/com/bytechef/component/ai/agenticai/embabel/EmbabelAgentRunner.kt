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

package com.bytechef.component.ai.agenticai.embabel

import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ActionRunner
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.DomainType
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.TYPE_LABELS_KEY
import com.embabel.agent.core.TYPE_NAME_KEY
import com.embabel.agent.core.ValuePropertyDefinition
import com.embabel.agent.core.support.AbstractAction
import com.embabel.agent.core.support.LlmCall
import com.embabel.agent.experimental.primitive.PromptCondition
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback

private val logger = LoggerFactory.getLogger(EmbabelAgentRunner::class.java)

private val objectMapper = ObjectMapper()

/**
 * A single property of a typed output binding's schema, as declared on canvas.
 *
 * [type] is a JSON-ish type label ("string", "number", "integer", "boolean", "array", "object")
 * used verbatim in the model instructions and in the [DynamicType]'s property definitions; it is
 * not validated against a closed set so the canvas options can evolve without a runner change.
 */
data class OutputProperty(
    val name: String,
    val type: String = "string",
    val description: String? = null,
)

/**
 * Describes a single GOAP action the planner may choose from.
 *
 * The planner uses [inputBinding] as the action's precondition (a value must exist under that name
 * on the blackboard) and [outputBinding] as its effect (after running, a value is written under
 * that name). Action list order is irrelevant — the planner decides sequencing, may skip actions
 * whose effects are unneeded, and may pick between alternatives when multiple actions produce the
 * same output binding.
 *
 * [cost] is the GOAP edge weight for this action. When several actions produce the same
 * [outputBinding], the planner prefers the path with the lowest total cost, so costs are the knob
 * users have for nudging the planner toward preferred alternatives (e.g., cheap/fast action vs.
 * expensive/high-quality action). A cost of `1.0` is a reasonable default; use higher values to
 * discourage an action and lower values to encourage it.
 *
 * [outputProperties] optionally declares a structured schema for the value this action writes to
 * [outputBinding]. When non-empty, the binding becomes *typed*: the action instructs the model to
 * return a JSON object with these properties, carries it on the blackboard as a
 * `_typeName`-tagged map (Embabel 1.0's dynamic domain-model carrier), and downstream consumers
 * receive the object as JSON instead of free text. All actions producing the same binding must
 * declare the same schema (or none).
 */
@SuppressFBWarnings("EI")
data class ActionStep @JvmOverloads constructor(
    val name: String,
    val description: String,
    val prompt: String,
    val inputBinding: String,
    val outputBinding: String,
    val toolCallbacks: List<ToolCallback>,
    val cost: Double = DEFAULT_ACTION_COST,
    val outputProperties: List<OutputProperty> = emptyList(),
) {
    companion object {
        /** Default per-action cost when the user does not specify one. */
        const val DEFAULT_ACTION_COST: Double = 1.0
    }
}

/**
 * Single carrier type for *untyped* values flowing between actions on the blackboard.
 *
 * Distinctness between untyped bindings comes from the *name* half of [IoBinding] (`name:type`),
 * not from JVM type identity — so untyped actions read and write [Binding], and the planner
 * discriminates by the binding name the user configured on canvas. Typed bindings (declared via
 * [ActionStep.outputProperties]) use `_typeName`-tagged maps instead, giving each binding a
 * [DynamicType] identity of its own.
 */
data class Binding(val content: String)

/**
 * GOAP action over dynamically-typed (schema-declared) blackboard bindings.
 *
 * Embabel's stock prompted transformer derives its [IoBinding]s from JVM classes, which makes
 * every canvas action interchangeable at the type level (everything is [Binding]). This action
 * instead declares its input/output bindings with *dynamic* type names backed by [DynamicType]
 * property schemas, and carries values as [TYPE_NAME_KEY]-tagged maps — Embabel 1.0's carrier for
 * runtime-declared domain models. Untyped sides fall back to the [Binding] carrier class, so
 * typed and untyped actions coexist in one agent.
 */
internal class DynamicTransformationAction(
    name: String,
    description: String,
    actionCost: Double,
    private val inputVarName: String,
    private val inputTypeName: String,
    private val outputVarName: String,
    outputTypeName: String,
    private val declaredDomainTypes: Collection<DomainType>,
    private val inputPropertyNames: Set<String>,
    private val block: (TransformationActionContext<Any, Any>) -> Any,
) : AbstractAction(
    name = name,
    description = description,
    cost = { _ -> actionCost },
    inputs = setOf(IoBinding(inputVarName, inputTypeName)),
    outputs = setOf(IoBinding(outputVarName, outputTypeName)),
    toolGroups = emptySet(),
    canRerun = false,
) {

    override val domainTypes: Collection<DomainType>
        get() = declaredDomainTypes

    override fun execute(processContext: ProcessContext): ActionStatus = ActionRunner.execute(processContext) {
        // getValue applies strict type matching (satisfiesType): a tagged map only satisfies its
        // own _typeName and a Binding only satisfies the Binding class — so a null here means an
        // upstream producer wrote a differently-typed value than this action's declared input.
        // The runner's schema-agreement validation should make this unreachable; fail loudly if not.
        val input = processContext.agentProcess.getValue(inputVarName, inputTypeName)
            ?: error(
                "Action '$name' found no value of type '$inputTypeName' at binding '$inputVarName'; " +
                    "an upstream action produced a value of a different type than this action expects."
            )

        val output = block(
            TransformationActionContext(
                input = input,
                processContext = processContext,
                action = this,
                inputClass = Any::class.java,
                outputClass = Any::class.java,
            )
        )

        processContext.blackboard[outputVarName] = output
    }

    override fun referencedInputProperties(variable: String): Set<String> = inputPropertyNames

    override fun toString() = "${javaClass.simpleName}: name=$name"
}

/**
 * Bridges ByteChef's canvas-authored agentic actions with Embabel's GOAP planner.
 *
 * Each [ActionStep] becomes an Embabel action whose precondition and effect are named blackboard
 * slots. The planner is given:
 *   - a seed binding named [USER_GOAL_BINDING] containing the user's goal description,
 *   - a goal satisfied by the presence of a binding named `goalOutputBinding`,
 *   - the full set of user-configured actions (order-independent).
 *
 * From that it selects a valid sequence of actions (or reports the goal is unreachable). When
 * multiple configured actions produce the same binding, the planner picks between them by
 * minimizing total plan cost (see [ActionStep.cost]) — so branching is exercised in structural mode
 * too, not only in smart-goal mode.
 *
 * **Typed bindings** (opt-in per action via [ActionStep.outputProperties]): a binding whose
 * producers declare an output schema is represented as an Embabel [DynamicType] named after the
 * binding, its values carried as [TYPE_NAME_KEY]-tagged maps. Actions touching a typed binding are
 * built as [DynamicTransformationAction]s: the model is instructed to return a JSON object with the
 * declared properties, the response is parsed and tagged, and downstream actions receive the
 * object rendered as JSON in their `{input}`. A typed goal binding makes [run] return the parsed
 * object (a `Map`) instead of a string. Untyped actions keep the [Binding] carrier and the plain
 * prompted-transformer path.
 *
 * **Smart goal mode** (opt-in via `smartGoal = true`): the structural `goalOutputBinding`
 * requirement is kept as the planner's target, and additionally a [PromptCondition] is attached to
 * the goal's preconditions. After the binding is produced, the LLM is asked whether its content
 * actually satisfies `goalDescription`. If not, the planner may backtrack and try an alternative
 * action path that also produces `goalOutputBinding` (the canvas may declare several). This adds an
 * LLM call per goal evaluation, so it is disabled by default. Uses the experimental
 * [PromptCondition] API; revisit when Embabel promotes it out of `experimental.primitive`.
 *
 * @author Ivica Cardic
 */
@SuppressFBWarnings("BC_BAD_CAST_TO_ABSTRACT_COLLECTION")
class EmbabelAgentRunner(private val agentPlatform: AgentPlatform) {

    fun run(
        actionSteps: List<ActionStep>,
        goalDescription: String,
        goalOutputBinding: String,
        smartGoal: Boolean,
        systemPrompt: String?,
    ): Any {
        require(actionSteps.isNotEmpty()) { "At least one action step is required" }
        require(goalOutputBinding.isNotBlank()) { "goalOutputBinding must not be blank" }
        require(actionSteps.any { it.outputBinding == goalOutputBinding }) {
            "No configured action produces the goal output binding '$goalOutputBinding'"
        }

        // Without at least one action reading from the seeded USER_GOAL_BINDING slot, the planner
        // has no entry point: every action's precondition would depend on an output that nothing
        // produces, and Embabel would report the goal unreachable at runtime. Fail up front with a
        // clear message so canvas misconfiguration surfaces at validation time instead.
        require(actionSteps.any { it.inputBinding == USER_GOAL_BINDING }) {
            "At least one action must use '$USER_GOAL_BINDING' as its inputBinding to serve as an entry point"
        }

        // Embabel identifies actions by name within an agent; duplicates would silently shadow each
        // other and destroy the planner's ability to choose between alternatives that produce the
        // same output binding. Reject up front with a clear message.
        val duplicateActionNames = actionSteps.groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        require(duplicateActionNames.isEmpty()) {
            "Duplicate action names are not allowed: $duplicateActionNames"
        }

        // Embabel encodes bindings as "name:type" strings, so a colon inside a binding name would
        // silently split into a bogus name/type pair at planning time.
        val bindingNamesWithColon = actionSteps.flatMap { listOf(it.inputBinding, it.outputBinding) }
            .plus(goalOutputBinding)
            .filter { it.contains(":") }
            .distinct()

        require(bindingNamesWithColon.isEmpty()) {
            "Binding names must not contain ':' (reserved as Embabel's name:type separator): $bindingNamesWithColon"
        }

        val bindingTypes = resolveBindingTypes(actionSteps)

        val goalConditions = if (smartGoal) {
            listOf(buildSmartGoalCondition(goalDescription, goalOutputBinding))
        } else {
            emptyList()
        }

        val embabelAgent = agent(
            name = "bytechef-agentic-ai",
            description = goalDescription,
        ) {
            for (actionStep in actionSteps) {
                val stepTools = actionStep.toolCallbacks.map { toEmbabelTool(it) }

                val stepCost = actionStep.cost

                val inputType = bindingTypes[actionStep.inputBinding]
                val outputType = bindingTypes[actionStep.outputBinding]

                if (inputType == null && outputType == null) {
                    promptedTransformer<Binding, Binding>(
                        name = actionStep.name,
                        description = actionStep.description,
                        inputVarName = actionStep.inputBinding,
                        outputVarName = actionStep.outputBinding,
                        cost = { _ -> stepCost },
                        tools = stepTools,
                    ) { context -> buildPrompt(actionStep, context.input.content, systemPrompt) }
                } else {
                    action { buildDynamicAction(actionStep, inputType, outputType, stepTools, systemPrompt) }
                }
            }

            goal(
                name = "achieve-goal",
                description = goalDescription,
                inputs = setOf(
                    IoBinding(
                        name = goalOutputBinding,
                        type = bindingTypes[goalOutputBinding]?.name ?: Binding::class.java.name,
                    )
                ),
                pre = goalConditions,
            )
        }

        // runAgentFrom accepts the agent directly (see AgentPlatform.runAgentFrom docs);
        // deploying is only required when other agents or the platform itself need to discover
        // this agent by name. For one-shot canvas runs we skip deploy to avoid accumulating
        // identically-named agents in the platform's registry.
        val agentProcess = agentPlatform.runAgentFrom(
            embabelAgent,
            buildProcessOptions(smartGoal),
            mapOf(USER_GOAL_BINDING to Binding(goalDescription)),
        )

        return extractGoalResult(agentProcess[goalOutputBinding], goalOutputBinding)
    }

    /**
     * Resolves the [DynamicType] for every binding whose producers declare an output schema.
     *
     * Type matching at execution time is strict (a tagged map only satisfies its own type name, a
     * [Binding] only the Binding class), so producers and consumers of one binding name must agree
     * on its typed-ness and shape — otherwise the planner would schedule an action whose input
     * lookup then finds nothing at runtime. All agreement violations are rejected here, up front.
     */
    private fun resolveBindingTypes(actionSteps: List<ActionStep>): Map<String, DynamicType> {
        val bindingTypes = mutableMapOf<String, DynamicType>()

        for ((bindingName, producers) in actionSteps.groupBy { it.outputBinding }) {
            val typedProducers = producers.filter { producer -> producer.outputProperties.isNotEmpty() }

            if (typedProducers.isEmpty()) {
                continue
            }

            require(bindingName != USER_GOAL_BINDING) {
                "Actions producing '$USER_GOAL_BINDING' must not declare an output schema: that binding is " +
                    "seeded with the plain-text goal description"
            }
            require(typedProducers.size == producers.size) {
                "All actions producing binding '$bindingName' must agree on its output schema, but only " +
                    "${typedProducers.size} of ${producers.size} declare one " +
                    "(producers: ${producers.map { it.name }})"
            }

            val distinctPropertyNameLists = producers
                .map { producer -> producer.outputProperties.map { it.name } }
                .distinct()

            require(distinctPropertyNameLists.size == 1) {
                "Actions producing binding '$bindingName' declare different output schemas: " +
                    "$distinctPropertyNameLists. Alternative producers of one binding must produce the same " +
                    "shape so downstream actions can consume any of them."
            }

            val outputProperties = producers.first().outputProperties

            require(outputProperties.none { it.name.isBlank() }) {
                "Binding '$bindingName' has an output schema property with a blank name"
            }

            val duplicatePropertyNames = outputProperties.groupingBy { it.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys

            require(duplicatePropertyNames.isEmpty()) {
                "Binding '$bindingName' declares duplicate output schema properties: $duplicatePropertyNames"
            }

            bindingTypes[bindingName] = DynamicType(
                name = dynamicTypeNameFor(bindingName),
                description = "Structured value of the '$bindingName' binding",
                ownProperties = outputProperties.map { outputProperty ->
                    ValuePropertyDefinition(
                        name = outputProperty.name,
                        type = outputProperty.type,
                        cardinality = Cardinality.ONE,
                        description = outputProperty.description ?: outputProperty.name,
                    )
                },
            )
        }

        return bindingTypes
    }

    private fun buildDynamicAction(
        actionStep: ActionStep,
        inputType: DynamicType?,
        outputType: DynamicType?,
        stepTools: List<Tool>,
        systemPrompt: String?,
    ): DynamicTransformationAction {
        val bindingTypeName = Binding::class.java.name

        return DynamicTransformationAction(
            name = actionStep.name,
            description = actionStep.description,
            actionCost = actionStep.cost,
            inputVarName = actionStep.inputBinding,
            inputTypeName = inputType?.name ?: bindingTypeName,
            outputVarName = actionStep.outputBinding,
            outputTypeName = outputType?.name ?: bindingTypeName,
            declaredDomainTypes = listOf(
                inputType ?: DynamicType(bindingTypeName),
                outputType ?: DynamicType(bindingTypeName),
            ),
            inputPropertyNames = inputType?.ownProperties
                ?.map { it.name }
                ?.toSet()
                ?: emptySet(),
        ) { context ->
            val prompt = buildPrompt(actionStep, renderInputContent(context.input), systemPrompt) +
                typedOutputInstructions(outputType)

            val responseText = context.promptRunner()
                .withTools(stepTools)
                .generateText(prompt)

            if (outputType == null) {
                Binding(responseText)
            } else {
                parseTypedOutput(responseText, outputType, actionStep.name)
            }
        }
    }

    private fun extractGoalResult(produced: Any?, goalOutputBinding: String): Any {
        return when {
            produced == null -> throw AgenticAiGoalNotAchievedException(
                "Agentic AI plan finished without producing a value at goal binding '$goalOutputBinding'. " +
                    "The planner may have exhausted its budget, failed smart-goal evaluation, or found the " +
                    "goal unreachable from the configured actions."
            )
            produced is Binding -> {
                if (produced.content.isEmpty()) {
                    throw AgenticAiGoalNotAchievedException(
                        "Agentic AI plan produced an empty value at goal binding '$goalOutputBinding'."
                    )
                }

                produced.content
            }
            produced is Map<*, *> -> {
                val structuredResult = LinkedHashMap<String, Any?>()

                produced.forEach { (key, value) ->
                    if (key != TYPE_NAME_KEY && key != TYPE_LABELS_KEY) {
                        structuredResult[key.toString()] = value
                    }
                }

                if (structuredResult.isEmpty()) {
                    throw AgenticAiGoalNotAchievedException(
                        "Agentic AI plan produced an empty object at goal binding '$goalOutputBinding'."
                    )
                }

                structuredResult
            }
            else -> throw AgenticAiGoalNotAchievedException(
                "Agentic AI plan wrote an unexpected type (${produced.javaClass.name}) at goal binding " +
                    "'$goalOutputBinding'; expected ${Binding::class.java.name} or a type-tagged map."
            )
        }
    }

    /**
     * Builds [ProcessOptions] with an explicit [Budget] so the planner's early-termination policy
     * is pinned to ByteChef-controlled values instead of Embabel's library defaults (which may
     * change across versions).
     *
     * Smart-goal mode can backtrack through alternative action paths when the LLM rejects a
     * produced value, so we grant it a higher action cap; structural mode runs a deterministic
     * plan and gets the tighter cap.
     */
    private fun buildProcessOptions(smartGoal: Boolean): ProcessOptions {
        val budget = if (smartGoal) {
            Budget(cost = SMART_GOAL_COST_LIMIT, actions = SMART_GOAL_ACTION_LIMIT, tokens = TOKEN_LIMIT)
        } else {
            Budget(cost = COST_LIMIT, actions = ACTION_LIMIT, tokens = TOKEN_LIMIT)
        }

        return ProcessOptions(budget = budget)
    }

    private fun toEmbabelTool(toolCallback: ToolCallback): Tool {
        val toolName = toolCallback.toolDefinition.name()

        return Tool.create(
            toolName,
            toolCallback.toolDefinition.description(),
        ) { toolInput ->
            try {
                Tool.Result.text(toolCallback.call(toolInput))
            } catch (e: Exception) {
                // Tool failures must not be swallowed into the generic "goal not achieved" error:
                // log the real cause with the tool name so operators can diagnose, then surface the
                // exception as a tool-level error the planner can see instead of a silent empty
                // result that would mask the failure as a budget/unreachable problem.
                logger.error("Tool '{}' invocation failed: {}", toolName, e.message, e)
                throw e
            }
        }
    }

    companion object {
        /**
         * Seed binding name for the user's goal description. Actions that should be considered as
         * entry points set their `inputBinding` to this value.
         */
        const val USER_GOAL_BINDING: String = "userGoal"

        /** Max actions a structural-goal plan may execute before early termination. */
        private const val ACTION_LIMIT: Int = 50

        /** Max actions a smart-goal plan may execute; higher to accommodate LLM-driven backtracking. */
        private const val SMART_GOAL_ACTION_LIMIT: Int = 75

        /** Max tokens any plan may consume before early termination. */
        private const val TOKEN_LIMIT: Int = 1_000_000

        /** Cost ceiling (USD) for structural-goal plans. */
        private const val COST_LIMIT: Double = 2.0

        /** Cost ceiling (USD) for smart-goal plans; higher to accommodate extra goal-evaluation LLM calls. */
        private const val SMART_GOAL_COST_LIMIT: Double = 3.0
    }
}

/**
 * Derives the [DynamicType] name for a typed binding. The type identity is a function of the
 * binding name (not of the producing action) so that alternative producers of one binding, its
 * consumers, and the goal all agree on the same type without extra canvas configuration.
 */
private fun dynamicTypeNameFor(bindingName: String): String =
    bindingName.replaceFirstChar { firstChar -> firstChar.uppercaseChar() }

/**
 * Renders an upstream blackboard value for inclusion in a downstream action's prompt: untyped
 * [Binding]s contribute their raw text, typed tagged maps are rendered as JSON with the internal
 * type-tag keys stripped.
 */
private fun renderInputContent(input: Any): String = when (input) {
    is Binding -> input.content
    is Map<*, *> -> objectMapper.writeValueAsString(
        input.filterKeys { key -> key != TYPE_NAME_KEY && key != TYPE_LABELS_KEY })
    else -> input.toString()
}

/**
 * Instruction block appended to an action's prompt when its output binding is typed, describing
 * the exact JSON object shape the model must return. Empty for untyped outputs.
 */
private fun typedOutputInstructions(outputType: DynamicType?): String {
    if (outputType == null) {
        return ""
    }

    return buildString {
        append("\n\nRespond with ONLY a JSON object — no markdown code fences, no commentary — ")
        append("containing exactly these properties:\n")

        for (property in outputType.ownProperties) {
            val propertyType = (property as? ValuePropertyDefinition)?.type ?: "string"

            append("- \"").append(property.name).append("\" (").append(propertyType).append(")")

            if (property.description.isNotBlank() && property.description != property.name) {
                append(": ").append(property.description)
            }

            append("\n")
        }
    }
}

/**
 * Parses a model response for a typed output binding into a [TYPE_NAME_KEY]-tagged map. Tolerates
 * markdown code fences around the JSON. Declared-but-missing properties are logged and tolerated
 * (partial objects are more useful than a failed plan); a response that is not a JSON object at
 * all fails the action with a diagnosable message.
 */
private fun parseTypedOutput(
    responseText: String,
    outputType: DynamicType,
    actionName: String,
): Map<String, Any?> {
    val json = stripCodeFences(responseText)

    val parsed: Map<*, *> = try {
        objectMapper.readValue(json, Map::class.java)
    } catch (e: JacksonException) {
        throw IllegalStateException(
            "Action '$actionName' declares typed output '${outputType.name}' but the model did not return a " +
                "parseable JSON object. Response starts with: '${json.take(200)}'",
            e,
        )
    }

    val missingProperties = outputType.ownProperties
        .map { it.name }
        .filter { propertyName -> !parsed.containsKey(propertyName) }

    if (missingProperties.isNotEmpty()) {
        logger.warn(
            "Action '{}' produced typed output '{}' without declared properties {}; continuing with the " +
                "partial object.",
            actionName, outputType.name, missingProperties,
        )
    }

    val taggedMap = LinkedHashMap<String, Any?>(parsed.size + 1)

    taggedMap[TYPE_NAME_KEY] = outputType.name

    parsed.forEach { (key, value) ->
        if (key != TYPE_NAME_KEY && key != TYPE_LABELS_KEY) {
            taggedMap[key.toString()] = value
        }
    }

    return taggedMap
}

private fun stripCodeFences(text: String): String {
    val trimmed = text.trim()

    if (!trimmed.startsWith("```")) {
        return trimmed
    }

    val withoutOpeningFence = trimmed.substringAfter("\n", missingDelimiterValue = "")

    return withoutOpeningFence.substringBeforeLast("```").trim()
}

/**
 * Merges the user-authored action prompt with the blackboard input and the optional system prompt.
 *
 * If the prompt contains the `{input}` placeholder, every occurrence is replaced with
 * [inputContent] (literal replacement — no regex interpretation, so `$` / `\` in the input are
 * safe). If the prompt contains no placeholder, the input is still delivered by appending a clearly
 * labeled `Input:` section, so the action never silently loses the upstream binding.
 */
private const val INPUT_PLACEHOLDER = "{input}"

private fun buildPrompt(actionStep: ActionStep, inputContent: String, systemPrompt: String?): String {
    val userPrompt = actionStep.prompt

    val basePrompt = if (userPrompt.contains(INPUT_PLACEHOLDER)) {
        userPrompt.replace(INPUT_PLACEHOLDER, inputContent)
    } else {
        buildString {
            append(userPrompt)
            append("\n\nInput:\n")
            append(inputContent)
        }
    }

    return buildString {
        append(basePrompt)

        if (!systemPrompt.isNullOrBlank()) {
            append("\n\nAdditional context:\n")
            append(systemPrompt)
        }
    }
}

/**
 * Builds an LLM-evaluated condition that asks whether the value currently bound to
 * [goalOutputBinding] semantically satisfies [goalDescription]. Uses the platform's default LLM
 * (the same one the action transformers resolve). The condition is named so log output and
 * Embabel's condition-caching can identify it; the name is stable across runs of the same agent
 * so the planner's condition memoization applies.
 */
private fun buildSmartGoalCondition(goalDescription: String, goalOutputBinding: String): PromptCondition {
    return PromptCondition(
        name = "smart-goal-$goalOutputBinding",
        prompt = { context ->
            val producedValue = when (val bound = context.processContext.agentProcess[goalOutputBinding]) {
                is Binding -> bound.content
                is Map<*, *> -> renderInputContent(bound)
                null -> "(nothing produced yet)"
                else -> {
                    logger.warn(
                        "Smart-goal condition for binding '{}' encountered unexpected bound type {}; " +
                            "falling back to toString(). This usually means an action wrote a value of the " +
                            "wrong type at the goal binding.",
                        goalOutputBinding, bound.javaClass.name,
                    )
                    bound.toString()
                }
            }

            """
            Goal: $goalDescription

            Current value at binding "$goalOutputBinding":
            $producedValue

            Does the current value satisfy the goal? Answer true only if the value directly and
            completely addresses the goal; answer false if it is missing information, off-topic, or
            only partially addresses the goal.
            """.trimIndent()
        },
        llm = LlmCall(),
    )
}
