/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

/**
 * One labelled entry of the bake-off corpus. {@code toolCount} and {@code maxTokens} are optional and exist so the
 * corpus can express the multi-tool-but-trivial and large-output buckets, which the deterministic baseline reads.
 *
 * @version ee
 */
public record BakeoffPrompt(
    String id, String text, String label, Integer toolCount, Integer maxTokens, String bucket, String rationale) {

    public static final String COMPLEX = "COMPLEX";
    public static final String SIMPLE = "SIMPLE";

    public boolean isComplex() {
        return COMPLEX.equals(label);
    }
}
