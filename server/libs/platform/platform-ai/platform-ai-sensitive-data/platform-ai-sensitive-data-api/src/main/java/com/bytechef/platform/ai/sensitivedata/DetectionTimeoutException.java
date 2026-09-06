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

package com.bytechef.platform.ai.sensitivedata;

/**
 * Thrown out of a running match when its deadline passes.
 *
 * <p>
 * This exists so that "a slow detector never throws" — the sentence the detector-bounds design turns on, and the reason
 * the engine's fail-open {@code catch} could not help — stops being true. A {@link MatchDeadline} makes a pathological
 * regex throw <em>this</em>, which {@code SensitiveDataRedactor} then treats as a timeout rather than as a detector
 * failure: the two are different facts and conflating them would lose the one that matters.
 * </p>
 *
 * @author Ivica Cardic
 */
public class DetectionTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DetectionTimeoutException(String message) {
        super(message);
    }
}
