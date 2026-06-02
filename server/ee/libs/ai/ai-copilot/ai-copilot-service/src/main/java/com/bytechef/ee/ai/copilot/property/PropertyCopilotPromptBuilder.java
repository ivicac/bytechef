/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.property;

import org.springframework.stereotype.Component;

/**
 * Builds prompts for the Property Copilot feature.
 *
 * @version ee
 * @author Ivica Cardic
 */
@Component
public class PropertyCopilotPromptBuilder {

    private static final String DEFAULT_PROMPT =
        "Infer the most appropriate value for the target property from its name, type, and the " +
            "available previous step outputs.";

    public String build(PropertyCopilotRequest request, String availableOutputs, String functionCatalog) {
        StringBuilder builder = new StringBuilder();

        String prompt = request.prompt();

        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_PROMPT;
        }

        builder.append(
            "You generate the value for a single workflow property based on the user's request.\n\n");
        builder.append("User request: ")
            .append(prompt)
            .append("\n\n");
        builder.append("Target property: ")
            .append(request.propertyPath());

        if (request.propertyType() != null) {
            builder.append(" (type ")
                .append(request.propertyType())
                .append(")");
        }

        builder.append("\n\nAvailable previous step outputs (reference these as ${nodeName.path}):\n")
            .append(availableOutputs)
            .append("\n");

        if (request.mode() == PropertyCopilotMode.FORMULA) {
            builder.append("Available functions (use ONLY these):\n")
                .append(functionCatalog)
                .append("\n");
            builder.append(
                "Return ONLY a single SpEL expression beginning with '='. Reference outputs as " +
                    "${nodeName.path}. Use only the listed functions. No explanation, no code fences.");
        } else {
            builder.append(
                "Return ONLY the literal text value for the property, embedding references to the " +
                    "outputs inline as ${nodeName.path} where appropriate. No explanation, no code fences.");
        }

        return builder.toString();
    }
}
