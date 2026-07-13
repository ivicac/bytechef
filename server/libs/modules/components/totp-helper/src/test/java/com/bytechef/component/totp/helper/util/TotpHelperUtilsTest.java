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

package com.bytechef.component.totp.helper.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class TotpHelperUtilsTest {

    // RFC 6238 test vector secret "12345678901234567890" is "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" in Base32

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void testGenerateTotpRfc6238TestVectors() {
        assertEquals("94287082", TotpHelperUtils.generateTotp(SECRET, 59, 30, 8));
        assertEquals("07081804", TotpHelperUtils.generateTotp(SECRET, 1111111109, 30, 8));
        assertEquals("14050471", TotpHelperUtils.generateTotp(SECRET, 1111111111, 30, 8));
        assertEquals("89005924", TotpHelperUtils.generateTotp(SECRET, 1234567890, 30, 8));
    }

    @Test
    void testGenerateTotpSixDigits() {
        assertEquals("287082", TotpHelperUtils.generateTotp(SECRET, 59, 30, 6));
    }

    @Test
    void testGenerateTotpInvalidSecret() {
        assertThrows(IllegalArgumentException.class, () -> TotpHelperUtils.generateTotp("abc!1", 59, 30, 6));
    }
}
