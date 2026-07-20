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

package com.bytechef.platform.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * @author Ivica Cardic
 */
class EmailNotificationClientTest {

    @Test
    void testSendWithoutMailSenderReturnsFalse() {
        EmailNotificationClient emailNotificationClient = new EmailNotificationClient(
            providerOf(null), "no-reply@example.com");

        assertThat(emailNotificationClient.send(List.of("ops@example.com"), "subject", "body")).isFalse();
    }

    @Test
    void testSendWithoutRecipientsReturnsFalse() {
        EmailNotificationClient emailNotificationClient = new EmailNotificationClient(
            providerOf(mock(JavaMailSender.class)), "no-reply@example.com");

        assertThat(emailNotificationClient.send(List.of(), "subject", "body")).isFalse();
    }

    @Test
    void testSendDeliversPlainTextMessage() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);

        EmailNotificationClient emailNotificationClient = new EmailNotificationClient(
            providerOf(javaMailSender), "no-reply@example.com");

        boolean sent = emailNotificationClient.send(List.of("ops@example.com"), "subject", "body");

        assertThat(sent).isTrue();

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);

        verify(javaMailSender).send(messageCaptor.capture());

        SimpleMailMessage message = messageCaptor.getValue();

        assertThat(message.getFrom()).isEqualTo("no-reply@example.com");
        assertThat(message.getTo()).containsExactly("ops@example.com");
        assertThat(message.getSubject()).isEqualTo("subject");
        assertThat(message.getText()).isEqualTo("body");
    }

    @Test
    void testSmtpFailurePropagatesAsIllegalState() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);

        doThrow(new MailSendException("smtp down")).when(javaMailSender)
            .send(any(SimpleMailMessage.class));

        EmailNotificationClient emailNotificationClient = new EmailNotificationClient(
            providerOf(javaMailSender), "no-reply@example.com");

        assertThatThrownBy(() -> emailNotificationClient.send(List.of("ops@example.com"), "subject", "body"))
            .isInstanceOf(IllegalStateException.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> providerOf(JavaMailSender javaMailSender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);

        when(provider.getIfAvailable()).thenReturn(javaMailSender);

        return provider;
    }
}
