/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.sampleoutput;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.copilot", name = "enabled", havingValue = "true")
@SuppressFBWarnings("EI")
public class SampleOutputCopilotGeneratorImpl implements SampleOutputCopilotGenerator {

    private static final ObjectMapper JSON_OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final SampleOutputPromptBuilder promptBuilder;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public SampleOutputCopilotGeneratorImpl(
        ChatModel chatModel, SampleOutputPromptBuilder promptBuilder,
        ObjectProvider<MeterRegistry> meterRegistryProvider) {

        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public SampleOutputCopilotResult generate(SampleOutputCopilotRequest request) {
        String prompt = promptBuilder.build(request.prompt());

        String value = clean(call(prompt));

        if (isValidJsonInstance(value)) {
            record("success");

            return new SampleOutputCopilotResult(value, true, null);
        }

        String repaired = clean(call(prompt +
            "\n\nThe previous attempt was not valid JSON. Return ONLY a valid JSON object or array."));

        if (isValidJsonInstance(repaired)) {
            record("success");

            return new SampleOutputCopilotResult(repaired, true, null);
        }

        record("invalid_json");

        return new SampleOutputCopilotResult(
            repaired, false, "The generated sample output could not be parsed; please review it.");
    }

    private String call(String promptText) {
        return chatModel.call(new Prompt(promptText))
            .getResult()
            .getOutput()
            .getText();
    }

    private static boolean isValidJsonInstance(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            JsonNode node = JSON_OBJECT_MAPPER.readTree(value);

            return node.isObject() || node.isArray();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }

        return text.replaceAll("```[a-zA-Z]*", "")
            .strip();
    }

    private void record(String outcome) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();

        if (meterRegistry == null) {
            return;
        }

        Counter.builder("bytechef_sample_output_copilot_generate")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }
}
