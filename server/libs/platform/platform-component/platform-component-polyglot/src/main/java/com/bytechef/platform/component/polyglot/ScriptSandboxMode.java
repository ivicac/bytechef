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

package com.bytechef.platform.component.polyglot;

/**
 * How much the guest context is allowed to reach outside itself.
 *
 * <p>
 * Not to be confused with {@link org.graalvm.polyglot.SandboxPolicy}, whose {@code TRUSTED} constant this enum's
 * {@code TRUSTED} does <strong>not</strong> mirror. {@link PolyglotSandbox} already builds
 * {@code SandboxPolicy.TRUSTED} contexts under {@link #STRICT} - for languages outside the constrained set, and when
 * the sandbox kill switch is off - and those keep every restriction: no host access, no IO, no process creation. The
 * policy governs which resource ceilings GraalVM can meter; this enum governs whether the guest may touch the host at
 * all. They are separate axes that happen to share a word.
 *
 * @author Ivica Cardic
 */
public enum ScriptSandboxMode {

    /**
     * No host access, no IO, no environment, no process or thread creation, and the configured CPU and heap ceilings
     * where the language supports them. The default for every caller that does not ask otherwise.
     */
    STRICT,

    /**
     * Full host access: class lookup and loading, native access, IO, the host environment, process and thread creation.
     * The guest runs inside this JVM with reflection, so it can reach the Spring context, the datasource and decrypted
     * credentials for every tenant. Single-tenant, trusted-operator deployments only.
     */
    TRUSTED
}
