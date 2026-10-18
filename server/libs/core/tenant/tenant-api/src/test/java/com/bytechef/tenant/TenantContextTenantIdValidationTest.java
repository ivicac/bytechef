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

package com.bytechef.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class TenantContextTenantIdValidationTest {

    @AfterEach
    void tearDown() {
        TenantContext.resetCurrentTenantId();
    }

    @Test
    void testSetCurrentTenantIdAcceptsValid() {
        assertThatCode(() -> TenantContext.setCurrentTenantId("000001")).doesNotThrowAnyException();
        assertThatCode(() -> TenantContext.setCurrentTenantId("public")).doesNotThrowAnyException();
    }

    @Test
    void testSetCurrentTenantIdRejectsInjection() {
        assertThatThrownBy(() -> TenantContext.setCurrentTenantId("public\"; DROP TABLE users; --"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRunWithTenantIdRejectsInjection() {
        assertThatThrownBy(() -> TenantContext.runWithTenantId("a;b", () -> {}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testCallWithTenantIdRejectsInjection() {
        assertThatThrownBy(() -> TenantContext.callWithTenantId("a;b", () -> "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testCallWithTenantIdRejectsAnInvalidTenantIdWithoutCalling() {
        AtomicBoolean called = new AtomicBoolean();

        TenantContext.setCurrentTenantId("000004");

        assertThatThrownBy(() -> TenantContext.callWithTenantId("bad id", () -> called.getAndSet(true)))
            .isExactlyInstanceOf(IllegalArgumentException.class);

        assertThat(called).isFalse();
        assertThat(TenantContext.getCurrentTenantId()).isEqualTo("000004");
    }

    @Test
    void testRunWithTenantIdRejectsAnInvalidTenantIdWithoutRunning() {
        AtomicBoolean called = new AtomicBoolean();

        TenantContext.setCurrentTenantId("000004");

        assertThatThrownBy(() -> TenantContext.runWithTenantId("bad.id", () -> called.set(true)))
            .isExactlyInstanceOf(IllegalArgumentException.class);

        assertThat(called).isFalse();
        assertThat(TenantContext.getCurrentTenantId()).isEqualTo("000004");
    }

    @Test
    void testCallWithTenantIdSwitchesAndRestoresTheTenant() {
        String tenantId = TenantContext.callWithTenantId("000002", TenantContext::getCurrentTenantId);

        assertThat(tenantId).isEqualTo("000002");
        assertThat(TenantContext.getCurrentTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
    }
}
