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

package com.bytechef.component.definition;

/**
 * How much damage one call of an action or tool can do. Declared by a component author; when undeclared the platform
 * infers a level from the operation's name. Governance surfaces use it to steer attention — it does not itself enforce
 * anything.
 *
 * @author Ivica Cardic
 */
public enum RiskLevel {

    /**
     * Reads. No state changes outside ByteChef.
     */
    LOW,

    /**
     * Ordinary writes: creating or updating a record the caller owns.
     */
    MEDIUM,

    /**
     * Reaches other people or systems: sending, publishing, granting, deploying.
     */
    HIGH,

    /**
     * Destroys data or moves money.
     */
    CRITICAL
}
