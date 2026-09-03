/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

/**
 * @version ee
 */
public record ClassificationResult(
    double accuracy, double precision, double recall, double areaUnderCurve, int count) {
}
