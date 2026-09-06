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
 * The two categories of sensitive data the guardrail policy layer can toggle independently. Deliberately closed: these
 * two values map one-to-one onto the {@code redactPii} / {@code redactSecrets} settings that already exist on both
 * {@code AiGuardrailsWorkspaceSettings} and the gateway's {@code AiGatewayProjectSettings}, and a third value would
 * leave those toggles unable to decide which spans they govern. The open axis is {@link SensitiveSpan#category()} — a
 * detector reporting a new entity type varies the category, never the kind.
 *
 * <p>
 * Not persisted anywhere, so ordinal stability is not a concern here.
 * </p>
 */
public enum SensitiveKind {

    PII,
    SECRET
}
