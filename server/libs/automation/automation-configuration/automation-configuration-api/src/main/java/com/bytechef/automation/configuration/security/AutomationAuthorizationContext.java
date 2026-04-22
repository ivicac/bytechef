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

package com.bytechef.automation.configuration.security;

/**
 * Thread-local flag marking that the current synchronous call stack is an embedded → automation delegation, during
 * which automation RBAC checks are bypassed.
 *
 * <p>
 * This flag is a security bypass, so its dangerous failure mode is staying set — which would silently grant access.
 * That open risk is contained three ways: the flag is always cleared via {@code try/finally} (an exception therefore
 * re-enforces checks), it is scoped narrowly to a single embedded facade operation, and it is never propagated across
 * threads (a plain {@link ThreadLocal} does not cross thread boundaries, so async work fails closed — the safe
 * direction).
 *
 * @author Ivica Cardic
 */
public final class AutomationAuthorizationContext {

    private static final ThreadLocal<Boolean> SKIP_CHECKS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private AutomationAuthorizationContext() {
    }

    public static boolean isSkipChecks() {
        return Boolean.TRUE.equals(SKIP_CHECKS.get());
    }

    /**
     * Runs {@code call} with the skip flag set, restoring the prior value in a {@code finally}. Declares
     * {@code throws Throwable} so an AOP aspect can pass {@code ProceedingJoinPoint::proceed} directly.
     */
    public static <V> V callSkippingChecks(SkippableCall<V> call) throws Throwable {
        boolean previous = isSkipChecks();

        SKIP_CHECKS.set(Boolean.TRUE);

        try {
            return call.call();
        } finally {
            if (previous) {
                SKIP_CHECKS.set(Boolean.TRUE);
            } else {
                SKIP_CHECKS.remove();
            }
        }
    }

    @FunctionalInterface
    public interface SkippableCall<V> {

        V call() throws Throwable;
    }
}
