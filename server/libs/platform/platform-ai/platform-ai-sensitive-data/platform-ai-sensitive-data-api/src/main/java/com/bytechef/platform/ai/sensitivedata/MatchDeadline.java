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

import java.time.Duration;

/**
 * Bounds how long a single regex match may run, by handing the engine a {@link CharSequence} that throws once a
 * deadline passes.
 *
 * <p>
 * <b>Why this shape.</b> {@code SensitiveDataRedactor}'s pass deadline is <em>cooperative</em> — checked between
 * detectors — so it bounds a whole pass but cannot interrupt one regex already inside catastrophic backtracking. That
 * was an acceptable limitation while every pattern was hand-reviewed and stopped being acceptable the moment
 * operator-supplied patterns became possible. A watchdog thread was the alternative; this needs no thread and no
 * interrupt cooperation from {@code java.util.regex}, which offers none.
 * </p>
 *
 * <p>
 * <b>It works because the engine reads through {@code charAt}, which was measured rather than assumed.</b> On this JVM,
 * {@code (x+x+)+y} against 1,000 {@code x}s runs 3,056ms unbounded and makes 664 million {@code charAt} calls — roughly
 * 200 million a second. A check every {@value #CHECK_INTERVAL} calls therefore fires about every 20 microseconds during
 * an explosion, and the same pattern under a 200ms deadline was interrupted at 200ms. On ordinary text the check is
 * unmeasurable: an email scan over 144,000 characters took the same time bounded as unbounded.
 * </p>
 *
 * <p>
 * <b>What it does not bound.</b> Deep recursion. {@code (a|aa)+$} against 4,000 {@code a}s raises
 * {@code StackOverflowError} in 8ms — faster than any useful deadline, and an {@link Error} rather than a
 * {@link RuntimeException}, so a {@code catch (RuntimeException)} does not see it either. That is a separate hazard and
 * {@code SensitiveDataRedactor} handles it separately; a deadline is the wrong tool for it.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class MatchDeadline {

    /**
     * How many {@code charAt} calls pass between clock reads. A power of two so the test is a mask rather than a
     * modulo, and large enough that {@code System.nanoTime()} is not on the hot path — but small enough that, at the
     * ~200 million calls a second an explosion produces, it still lands within tens of microseconds of the deadline.
     */
    private static final int CHECK_INTERVAL = 4096;

    private final long deadlineNanos;

    private MatchDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    /**
     * Returns a deadline expiring {@code budget} from now.
     *
     * @param budget how long matching may take
     * @return the deadline
     */
    public static MatchDeadline in(Duration budget) {
        return new MatchDeadline(System.nanoTime() + budget.toNanos());
    }

    /**
     * Returns a deadline expiring at an already-computed {@code System#nanoTime} reading, so a caller that already
     * tracks a pass deadline can hand matching the same one rather than a second, later budget.
     *
     * @param deadlineNanos the expiry, on {@code System#nanoTime}'s clock
     * @return the deadline
     */
    public static MatchDeadline at(long deadlineNanos) {
        return new MatchDeadline(deadlineNanos);
    }

    /**
     * Returns a deadline that never expires, for callers with no budget to enforce.
     *
     * @return an unbounded deadline
     */
    public static MatchDeadline unbounded() {
        return new MatchDeadline(Long.MAX_VALUE);
    }

    /**
     * Wraps {@code text} so that a match running past this deadline throws {@link DetectionTimeoutException}.
     *
     * <p>
     * Hand the result to {@code Pattern#matcher}. Note that {@code Matcher#group()} returns a plain {@code String} —
     * the wrapper bounds the scan, not what a caller does with a match it already found.
     * </p>
     *
     * @param text the text to scan
     * @return a deadline-checking view of {@code text}
     */
    public CharSequence bound(String text) {
        if (deadlineNanos == Long.MAX_VALUE) {
            // Nothing to enforce, so hand back the String itself rather than paying for a wrapper that can never
            // fire. Keeps the unbounded path byte-for-byte what it was before this class existed.
            return text;
        }

        return new BoundedCharSequence(text, deadlineNanos);
    }

    /**
     * Returns whether this deadline has already passed, for a caller checking between units of work rather than inside
     * one.
     *
     * @return whether the deadline has expired
     */
    public boolean expired() {
        return System.nanoTime() >= deadlineNanos;
    }

    /**
     * A {@code CharSequence} view that checks the clock every {@link #CHECK_INTERVAL} reads.
     *
     * <p>
     * Not thread-safe, and deliberately not: {@code call} is a plain field because a {@code Matcher} is confined to one
     * thread anyway, and making the counter atomic would put a contended write on the hot path for no benefit. A fresh
     * instance per match is the contract.
     * </p>
     */
    private static final class BoundedCharSequence implements CharSequence {

        private final String delegate;
        private final long deadlineNanos;

        private long calls;

        private BoundedCharSequence(String delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if ((++calls & (CHECK_INTERVAL - 1)) == 0 && System.nanoTime() >= deadlineNanos) {
                throw new DetectionTimeoutException(
                    "match exceeded its deadline after " + calls + " character reads over a " + delegate.length() +
                        "-character input");
            }

            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate;
        }
    }
}
