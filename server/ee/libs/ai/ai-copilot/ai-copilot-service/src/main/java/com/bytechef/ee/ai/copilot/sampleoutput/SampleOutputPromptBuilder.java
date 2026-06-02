/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.sampleoutput;

import org.springframework.stereotype.Component;

/**
 * Builds prompts for the Sample Output Copilot feature.
 *
 * @version ee
 * @author Ivica Cardic
 */
@Component
public class SampleOutputPromptBuilder {

    public String build(String prompt) {
        StringBuilder builder = new StringBuilder();

        builder.append(
            "You generate a single realistic example data instance in JSON based on the user's description. ")
            .append("Return example data (a concrete JSON object or array with sample values), ")
            .append("not a schema definition.\n\n");
        builder.append("User request: ")
            .append(prompt)
            .append("\n\n");
        builder.append(
            "Return ONLY the JSON instance. No explanation, no markdown, no code fences.");

        return builder.toString();
    }
}
