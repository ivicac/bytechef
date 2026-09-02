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

package com.bytechef.platform.component.runner.external;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the head and the tail of a stream that may be arbitrarily long.
 *
 * <p>
 * A runaway process can emit gigabytes, and a task's stored output cannot. Truncating the tail loses the failure;
 * truncating the head loses the banner that says what actually ran. So both ends are kept and the middle is elided,
 * with a marker stating how much went.
 *
 * <p>
 * The kept bytes are decoded as UTF-8 with malformed input replaced, because an elision boundary can fall inside a
 * multi-byte character. Losing one character to a replacement marker at the cut is the correct trade against refusing
 * to decode. When nothing was elided, the head and tail bytes are joined into one array and decoded together, not
 * decoded separately and concatenated as strings - the stream is contiguous there, and a multi-byte character can
 * legitimately straddle the internal 32 KiB seam between the two buffers even though no byte was lost. Decoding each
 * half on its own would see two fragments of that one character and replace each fragment independently. Only the
 * elision case decodes the two halves apart, because there the bytes really are discontinuous and a replacement
 * character at each edge is the honest result.
 *
 * <p>
 * {@link #append(byte[], int)} runs on the thread draining the child process's stream, while {@link #get()} and
 * {@link #getTotalLength()} run on the task thread once the process has finished. The methods are {@code
 * synchronized} so a read after the drain thread has joined always observes every appended byte.
 *
 * @author Ivica Cardic
 */
public final class BoundedOutputCapture {

    private static final int HALF_LIMIT = 32 * 1024;

    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private final byte[] tail = new byte[HALF_LIMIT];

    private int tailLength;
    private int tailPosition;
    private long totalLength;

    /**
     * Appends the first {@code length} bytes of {@code buffer}.
     */
    public synchronized void append(byte[] buffer, int length) {
        for (int index = 0; index < length; index++) {
            byte value = buffer[index];

            if (head.size() < HALF_LIMIT) {
                head.write(value);
            } else {
                tail[tailPosition] = value;
                tailPosition = (tailPosition + 1) % HALF_LIMIT;

                if (tailLength < HALF_LIMIT) {
                    tailLength++;
                }
            }
        }

        totalLength += length;
    }

    /**
     * Returns the captured text, with the elided middle marked when the stream exceeded the limit.
     */
    public synchronized String get() {
        byte[] headBytes = head.toByteArray();

        if (tailLength == 0) {
            return decode(headBytes);
        }

        byte[] tailBytes = orderedTail();

        long elided = totalLength - headBytes.length - tailLength;

        if (elided <= 0) {
            return decode(concatenate(headBytes, tailBytes));
        }

        return decode(headBytes) + "%n... %d bytes elided ...%n".formatted(elided) + decode(tailBytes);
    }

    /**
     * Returns the number of bytes appended so far, including bytes that were elided.
     */
    public synchronized long getTotalLength() {
        return totalLength;
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];

        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);

        return combined;
    }

    private byte[] orderedTail() {
        byte[] ordered = new byte[tailLength];

        int start = tailLength < HALF_LIMIT ? 0 : tailPosition;

        for (int index = 0; index < tailLength; index++) {
            ordered[index] = tail[(start + index) % HALF_LIMIT];
        }

        return ordered;
    }

    private static String decode(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);

        try {
            CharBuffer charBuffer = decoder.decode(ByteBuffer.wrap(bytes));

            return charBuffer.toString();
        } catch (CharacterCodingException exception) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
