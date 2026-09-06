/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.tokenization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class PiiTokenTest {

    @Test
    void testRendersTheDocumentedFormat() {
        assertThat(new PiiToken("EMAIL", 1, "k3n9").text()).isEqualTo("[PII_EMAIL_1_k3n9]");
    }

    @Test
    void testParsesWhatItRenders() {
        PiiToken token = new PiiToken("ORGANIZATION", 12, "ab12");

        assertThat(PiiToken.parse(token.text())).contains(token);
    }

    @Test
    void testRejectsTextThatIsNotAToken() {
        assertThat(PiiToken.parse("[REDACTED_EMAIL]")).isEmpty();
        assertThat(PiiToken.parse("[PII_EMAIL_1]")).isEmpty();
        assertThat(PiiToken.parse("[PII_email_1_k3n9]")).isEmpty();
        assertThat(PiiToken.parse("plain text")).isEmpty();
        assertThat(PiiToken.parse("[PII_EMAIL_99999999999999999999_k3n9]")).isEmpty();
    }

    @Test
    void testPatternFindsEveryTokenInASentence() {
        String text = "forward [PII_EMAIL_1_k3n9] to [PII_EMAIL_2_k3n9] now";

        assertThat(PiiToken.pattern()
            .matcher(text)
            .results()
            .count()).isEqualTo(2);
    }

    @Test
    void testRejectsInvalidComponents() {
        assertThatThrownBy(() -> new PiiToken("lower", 1, "k3n9")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PiiToken("EMAIL", 0, "k3n9")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PiiToken("EMAIL", 1, "TOOLONG")).isInstanceOf(IllegalArgumentException.class);
    }
}
