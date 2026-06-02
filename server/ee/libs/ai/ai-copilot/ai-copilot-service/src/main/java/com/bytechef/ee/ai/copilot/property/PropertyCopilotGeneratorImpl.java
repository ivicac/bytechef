/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.property;

import com.bytechef.evaluator.Evaluator;
import com.bytechef.evaluator.EvaluatorFunctionDefinition;
import com.bytechef.evaluator.EvaluatorFunctionDefinitionFactory;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.dto.WorkflowNodeOutputDTO;
import com.bytechef.platform.configuration.facade.WorkflowNodeOutputFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.copilot", name = "enabled", havingValue = "true")
@SuppressFBWarnings("EI")
public class PropertyCopilotGeneratorImpl implements PropertyCopilotGenerator {

    private final ChatModel chatModel;
    private final Evaluator evaluator;
    private final PropertyCopilotPromptBuilder promptBuilder;
    private final List<EvaluatorFunctionDefinitionFactory> evaluatorFunctionDefinitionFactories;
    private final WorkflowNodeOutputFacade workflowNodeOutputFacade;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public PropertyCopilotGeneratorImpl(
        ChatModel chatModel, Evaluator evaluator, PropertyCopilotPromptBuilder promptBuilder,
        List<EvaluatorFunctionDefinitionFactory> evaluatorFunctionDefinitionFactories,
        WorkflowNodeOutputFacade workflowNodeOutputFacade, ObjectProvider<MeterRegistry> meterRegistryProvider) {

        this.chatModel = chatModel;
        this.evaluator = evaluator;
        this.promptBuilder = promptBuilder;
        this.evaluatorFunctionDefinitionFactories = evaluatorFunctionDefinitionFactories;
        this.workflowNodeOutputFacade = workflowNodeOutputFacade;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public PropertyCopilotResult generate(PropertyCopilotRequest request) {
        String availableOutputs = buildAvailableOutputs(request);
        String functionCatalog =
            request.mode() == PropertyCopilotMode.FORMULA ? buildFunctionCatalog() : "";

        String prompt = promptBuilder.build(request, availableOutputs, functionCatalog);
        String value = clean(call(prompt));

        if (request.mode() != PropertyCopilotMode.FORMULA) {
            record(request, "success");

            return new PropertyCopilotResult(value, true, null);
        }

        if (!value.startsWith("=")) {
            value = "=" + value;
        }

        Map<String, ?> context = workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(
            request.workflowId(), request.workflowNodeName(), request.environmentId());

        if (isValidFormula(value, context)) {
            record(request, "success");

            return new PropertyCopilotResult(value, true, null);
        }

        String repaired = clean(call(prompt +
            "\n\nThe previous attempt was not a valid expression. Return a corrected single '=' expression."));

        if (!repaired.startsWith("=")) {
            repaired = "=" + repaired;
        }

        if (isValidFormula(repaired, context)) {
            record(request, "success");

            return new PropertyCopilotResult(repaired, true, null);
        }

        record(request, "invalid_formula");

        return new PropertyCopilotResult(
            repaired, false, "The generated formula could not be validated; please review it.");
    }

    private String buildAvailableOutputs(PropertyCopilotRequest request) {
        List<WorkflowNodeOutputDTO> outputs = workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(
            request.workflowId(), request.workflowNodeName(), request.environmentId());

        StringBuilder builder = new StringBuilder("\n");

        for (WorkflowNodeOutputDTO output : outputs) {
            builder.append(output.workflowNodeName())
                .append(": ")
                .append(output.getSampleOutput())
                .append("\n");
        }

        return builder.toString();
    }

    private String buildFunctionCatalog() {
        StringBuilder builder = new StringBuilder();

        for (EvaluatorFunctionDefinitionFactory factory : evaluatorFunctionDefinitionFactories) {
            for (EvaluatorFunctionDefinition definition : factory.getDefinitions()) {
                builder.append("- ")
                    .append(definition.name())
                    .append(": ")
                    .append(definition.description())
                    .append("\n");
            }
        }

        return builder.toString();
    }

    private String call(String promptText) {
        return chatModel.call(new Prompt(promptText))
            .getResult()
            .getOutput()
            .getText();
    }

    private boolean isValidFormula(String value, Map<String, ?> context) {
        try {
            evaluator.evaluate(Map.of("value", value), context, false);

            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }

        return text.replace("```", "")
            .strip();
    }

    private void record(PropertyCopilotRequest request, String outcome) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();

        if (meterRegistry == null) {
            return;
        }

        Counter.builder("bytechef_property_copilot_generate")
            .tag("mode", request.mode()
                .name())
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }
}
