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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * @author Ivica Cardic
 */
public class TotpHelperUtils {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpHelperUtils() {
    }

    public static String generateTotp(String secret, long timeSeconds, int period, int digits) {
        long counter = timeSeconds / period;

        byte[] key = decodeBase32(secret);
        byte[] data = new byte[8];

        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xff);

            counter >>= 8;
        }

        byte[] hash;

        try {
            Mac mac = Mac.getInstance("HmacSHA1");

            mac.init(new SecretKeySpec(key, "RAW"));

            hash = mac.doFinal(data);
        } catch (GeneralSecurityException generalSecurityException) {
            throw new IllegalStateException("Unable to generate TOTP code", generalSecurityException);
        }

        int offset = hash[hash.length - 1] & 0xf;

        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);

        int otp = binary % (int) Math.pow(10, digits);

        return String.format("%0" + digits + "d", otp);
    }

    private static byte[] decodeBase32(String secret) {
        String normalizedSecret = secret.trim()
            .replace(" ", "")
            .replace("=", "")
            .toUpperCase();

        byte[] normalizedSecretBytes = normalizedSecret.getBytes(StandardCharsets.US_ASCII);

        int bitBuffer = 0;
        int bitCount = 0;
        int index = 0;

        byte[] result = new byte[normalizedSecretBytes.length * 5 / 8];

        for (byte normalizedSecretByte : normalizedSecretBytes) {
            int value = BASE32_ALPHABET.indexOf(normalizedSecretByte);

            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 character in secret");
            }

            bitBuffer = (bitBuffer << 5) | value;
            bitCount += 5;

            if (bitCount >= 8) {
                result[index++] = (byte) ((bitBuffer >> (bitCount - 8)) & 0xff);

                bitCount -= 8;
            }
        }

        return result;
    }
}
