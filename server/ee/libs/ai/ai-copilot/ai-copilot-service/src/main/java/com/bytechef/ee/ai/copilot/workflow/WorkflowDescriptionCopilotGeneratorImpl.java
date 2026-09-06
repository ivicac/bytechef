/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.workflow;

import com.bytechef.ai.copilot.advisor.CopilotGuardrailsAdvisorFactory;
import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
public class WorkflowDescriptionCopilotGeneratorImpl implements WorkflowDescriptionCopilotGenerator {

    private final ChatModel chatModel;
    private final CopilotGuardrailsAdvisorFactory copilotGuardrailsAdvisorFactory;
    private final WorkflowService workflowService;
    private final WorkflowDescriptionPromptBuilder promptBuilder;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public WorkflowDescriptionCopilotGeneratorImpl(
        ChatModel chatModel, CopilotGuardrailsAdvisorFactory copilotGuardrailsAdvisorFactory,
        WorkflowService workflowService, WorkflowDescriptionPromptBuilder promptBuilder,
        ObjectProvider<MeterRegistry> meterRegistryProvider) {

        this.chatModel = chatModel;
        this.copilotGuardrailsAdvisorFactory = copilotGuardrailsAdvisorFactory;
        this.workflowService = workflowService;
        this.promptBuilder = promptBuilder;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public WorkflowDescriptionCopilotResult generate(WorkflowDescriptionCopilotRequest request) {
        Workflow workflow = workflowService.getWorkflow(request.workflowId());

        String prompt = promptBuilder.build(workflow.getDefinition(), request.workflowNodeName());

        String value = clean(call(prompt));

        record(request.workflowNodeName() == null ? "workflow" : "node");

        return new WorkflowDescriptionCopilotResult(value);
    }

    /**
     * Calls the model through a guarded {@link org.springframework.ai.chat.client.ChatClient} rather than
     * {@code chatModel.call(new Prompt(...))}. A workflow definition carries whatever the workflow author typed into
     * its step parameters - recipient addresses, account identifiers, occasionally a pasted credential - and this
     * generator sends the whole definition to the model provider. {@code AiGuardrailsAdvisor} is a {@code ChatClient}
     * advisor, so a direct {@link ChatModel} call cannot be intercepted by it at all.
     */
    private String call(String promptText) {
        ChatResponse chatResponse = copilotGuardrailsAdvisorFactory.guardedChatClient(chatModel)
            .prompt(promptText)
            .call()
            .chatResponse();

        Objects.requireNonNull(chatResponse, "chat response is required");

        Generation generation = Objects.requireNonNull(chatResponse.getResult(), "generation is required");

        return generation.getOutput()
            .getText();
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }

        return text.replaceAll("```[a-zA-Z]*", "")
            .strip();
    }

    private void record(String scope) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();

        if (meterRegistry == null) {
            return;
        }

        Counter.builder("bytechef_workflow_description_copilot_generate")
            .tag("scope", scope)
            .tag("outcome", "success")
            .register(meterRegistry)
            .increment();
    }
}
