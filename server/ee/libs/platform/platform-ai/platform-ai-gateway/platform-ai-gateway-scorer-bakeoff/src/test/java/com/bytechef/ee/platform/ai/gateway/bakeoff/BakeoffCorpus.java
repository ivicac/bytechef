/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayTool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads a JSONL bake-off corpus from module test resources and turns entries into gateway chat completion requests.
 *
 * @version ee
 */
public final class BakeoffCorpus {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .build();

    private static final String RESOURCE_PREFIX = "/bakeoff/";

    private BakeoffCorpus() {
    }

    public static List<BakeoffPrompt> load(String resourceName) {
        List<BakeoffPrompt> prompts = new ArrayList<>();

        try (InputStream inputStream = BakeoffCorpus.class.getResourceAsStream(RESOURCE_PREFIX + resourceName)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Corpus resource not found: " + resourceName);
            }

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();

                    if (trimmed.isEmpty()) {
                        continue;
                    }

                    prompts.add(OBJECT_MAPPER.readValue(trimmed, BakeoffPrompt.class));
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read corpus " + resourceName, exception);
        }

        return prompts;
    }

    public static AiGatewayChatCompletionRequest toRequest(BakeoffPrompt prompt) {
        List<AiGatewayChatMessage> messages = List.of(
            new AiGatewayChatMessage(AiGatewayChatRole.USER, prompt.text()));

        List<AiGatewayTool> tools = null;

        Integer toolCount = prompt.toolCount();

        if (toolCount != null && toolCount > 0) {
            tools = new ArrayList<>();

            for (int index = 0; index < toolCount; index++) {
                tools.add(
                    new AiGatewayTool(
                        "function",
                        new AiGatewayTool.AiGatewayToolFunction("tool" + index, "description", Map.of())));
            }
        }

        return new AiGatewayChatCompletionRequest(
            "bakeoff", messages, null, prompt.maxTokens(), null, false, null, null, null, tools);
    }
}
