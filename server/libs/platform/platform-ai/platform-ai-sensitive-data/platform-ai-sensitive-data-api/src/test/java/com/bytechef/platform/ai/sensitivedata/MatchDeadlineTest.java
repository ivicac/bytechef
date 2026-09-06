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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * @author Ivica Cardic
 */
class MatchDeadlineTest {

    /**
     * The pattern and input this whole class exists for, chosen by measurement rather than reputation.
     *
     * <p>
     * Several textbook ReDoS patterns — {@code (a+)+$}, {@code (a|aa)+$}, {@code (a*)*b}, {@code ([a-zA-Z]+)*$} — do
     * NOT explode in this JVM's engine at any input length worth testing; they complete in under a millisecond. This
     * one does: unbounded it runs about three seconds on a 1,000-character input and makes roughly 664 million
     * character reads. Substituting a "more famous" pattern here would make this test vacuous.
     * </p>
     */
    private static final Pattern CATASTROPHIC = Pattern.compile("(x+x+)+y");

    private static final String CATASTROPHIC_INPUT = "x".repeat(1000);

    /**
     * The load-bearing test. Its {@link Timeout} is the assertion as much as the exception type is: if the deadline did
     * not interrupt, this method would run for seconds rather than failing, and a plain {@code assertThatThrownBy}
     * would hang the build instead of reporting.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testAMatchRunningPastTheDeadlineIsInterrupted() {
        MatchDeadline deadline = MatchDeadline.in(Duration.ofMillis(100));

        long start = System.nanoTime();

        assertThatThrownBy(
            () -> CATASTROPHIC.matcher(deadline.bound(CATASTROPHIC_INPUT))
                .find())
                    .isInstanceOf(DetectionTimeoutException.class)
                    .hasMessageContaining("deadline");

        // Unbounded this input runs ~3s. Anything under a second proves the interruption happened rather than the
        // match simply finishing.
        assertThat(Duration.ofNanos(System.nanoTime() - start))
            .isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testAnAlreadyExpiredDeadlineInterruptsImmediately() {
        MatchDeadline deadline = MatchDeadline.at(System.nanoTime() - 1);

        assertThatThrownBy(
            () -> CATASTROPHIC.matcher(deadline.bound(CATASTROPHIC_INPUT))
                .find())
                    .isInstanceOf(DetectionTimeoutException.class);
    }

    @Test
    void testAnOrdinaryMatchIsUnaffected() {
        // The other half: bounding must not change what a normal scan finds, or every detector's behaviour would
        // depend on how much budget was left when it ran.
        Pattern email = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
        MatchDeadline deadline = MatchDeadline.in(Duration.ofSeconds(30));

        var matcher = email.matcher(deadline.bound("write to bob@acme.io today"));

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo("bob@acme.io");
        assertThat(matcher.start()).isEqualTo(9);
        assertThat(matcher.end()).isEqualTo(20);
    }

    @Test
    void testAnUnboundedDeadlineHandsBackTheStringItself() {
        // Not an optimisation detail: the unbounded path must stay byte-for-byte what it was before this class
        // existed, so a caller with no budget pays literally nothing.
        String text = "write to bob@acme.io today";

        assertThat(MatchDeadline.unbounded()
            .bound(text)).isSameAs(text);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testAnUnboundedDeadlineDoesNotInterrupt() {
        assertThatCode(
            () -> CATASTROPHIC.matcher(
                MatchDeadline.unbounded()
                    .bound("x".repeat(50)))
                .find())
                    .doesNotThrowAnyException();
    }

    @Test
    void testTheBoundedViewReportsTheSameContentAsTheString() {
        String text = "write to bob@acme.io today";
        CharSequence bounded = MatchDeadline.in(Duration.ofSeconds(30))
            .bound(text);

        assertThat(bounded.length()).isEqualTo(text.length());
        assertThat(bounded.charAt(0)).isEqualTo('w');
        assertThat(bounded.subSequence(9, 20)).isEqualTo("bob@acme.io");
        assertThat(bounded).hasToString(text);
    }

    @Test
    void testExpiredReportsTheDeadlineForBetweenUnitChecks() {
        assertThat(MatchDeadline.at(System.nanoTime() - 1)
            .expired()).isTrue();
        assertThat(MatchDeadline.in(Duration.ofSeconds(30))
            .expired()).isFalse();
        assertThat(MatchDeadline.unbounded()
            .expired()).isFalse();
    }
}
