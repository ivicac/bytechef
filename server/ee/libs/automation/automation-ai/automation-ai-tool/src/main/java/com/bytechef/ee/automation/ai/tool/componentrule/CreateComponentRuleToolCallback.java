/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.componentrule;

import com.bytechef.ai.agent.tool.ToolErrors;
import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.exception.ConfigurationException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring AI {@link ToolCallback} that persists a component rule. The only write in the Component Rule tool family:
 * {@link ProposeComponentRuleConditionToolCallback} validates and hands a condition back for review, this tool alone
 * touches the database.
 *
 * <p>
 * The {@code AFTER}+{@code BLOCK} rejection duplicates {@link ComponentRuleService#saveComponentRule}'s own guard on
 * purpose: the service guard is the real invariant and stays, this one exists only to give the model a corrigible
 * message in the same turn instead of a stack trace.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class CreateComponentRuleToolCallback implements ToolCallback {

    static final String TOOL_NAME = "createComponentRule";

    private static final String DESCRIPTION = """
        Create a component rule. Call proposeComponentRuleCondition first and confirm the condition with the admin
        before calling this — this tool writes to the database. An AFTER-phase rule must use TAG; BLOCK is rejected,
        because the action has already run by then. Returns the new rule's id.""";

    private static final String INPUT_SCHEMA =
        """
            {
                "type": "object",
                "properties": {
                    "componentName": {"type": "string", "description": "The component the rule governs"},
                    "actionName": {"type": "string", "description": "Optional — omit to govern every action of the component"},
                    "phase": {"type": "string", "description": "BEFORE or AFTER"},
                    "ruleAction": {"type": "string", "description": "BLOCK or TAG. AFTER rules must be TAG."},
                    "condition": {"type": "string", "description": "The formula body, validated by proposeComponentRuleCondition first"},
                    "description": {"type": "string", "description": "The plain-English intent, kept for later re-editing"}
                },
                "required": ["componentName", "phase", "ruleAction", "condition"]
            }""";

    private final ComponentRuleService componentRuleService;
    private final JsonMapper jsonMapper = new JsonMapper();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public CreateComponentRuleToolCallback(ComponentRuleService componentRuleService) {
        this.componentRuleService = componentRuleService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(INPUT_SCHEMA)
            .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        try {
            CreateComponentRuleInput input = jsonMapper.readValue(toolInput, CreateComponentRuleInput.class);

            if (input.componentName() == null || input.componentName()
                .isBlank()) {
                return toolError("componentName is required");
            }

            if (input.condition() == null || input.condition()
                .isBlank()) {
                return toolError("condition is required");
            }

            if (input.phase() == null || input.phase()
                .isBlank()) {
                return toolError("phase is required (BEFORE or AFTER)");
            }

            RulePhase phase;

            try {
                phase = RulePhase.valueOf(input.phase());
            } catch (IllegalArgumentException exception) {
                return toolError("phase must be BEFORE or AFTER, got '" + input.phase() + "'");
            }

            if (input.ruleAction() == null || input.ruleAction()
                .isBlank()) {
                return toolError("ruleAction is required (BLOCK or TAG)");
            }

            RuleAction ruleAction;

            try {
                ruleAction = RuleAction.valueOf(input.ruleAction());
            } catch (IllegalArgumentException exception) {
                return toolError("ruleAction must be BLOCK or TAG, got '" + input.ruleAction() + "'");
            }

            if (phase == RulePhase.AFTER && ruleAction == RuleAction.BLOCK) {
                return toolError(
                    "A rule evaluated in the AFTER phase cannot BLOCK — the action has already run. Use TAG "
                        + "instead.");
            }

            ComponentRule componentRule = new ComponentRule();

            componentRule.setComponentName(input.componentName());
            componentRule.setActionName(input.actionName());
            componentRule.setPhase(phase);
            componentRule.setRuleAction(ruleAction);
            componentRule.setCondition(input.condition());
            componentRule.setDescription(input.description());

            ComponentRule savedComponentRule = componentRuleService.saveComponentRule(componentRule);

            return jsonMapper.writeValueAsString(new CreateComponentRuleOutput(savedComponentRule.getId()));
        } catch (JacksonException exception) {
            return toolError("Invalid tool input: " + exception.getMessage());
        } catch (IllegalArgumentException | ConfigurationException exception) {
            return toolError(exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolErrors.runtimeFailure(jsonMapper, CreateComponentRuleToolCallback.class, TOOL_NAME, exception);
        }
    }

    private String toolError(String message) {
        return ToolErrors.toolError(jsonMapper, message);
    }

    public record CreateComponentRuleInput(
        String componentName, @Nullable String actionName, String phase, String ruleAction, String condition,
        @Nullable String description) {
    }

    public record CreateComponentRuleOutput(@Nullable Long componentRuleId) {
    }
}
