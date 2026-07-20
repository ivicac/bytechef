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

import com.bytechef.commons.util.UrlValidationException;
import com.bytechef.commons.util.UrlValidator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The single outbound-webhook transport for every notification surface — platform notifications (job status), the EE
 * AI-observability alert channels, and future alert rules. Owns SSRF validation, standard headers, optional HMAC
 * signing, and error mapping so no caller re-implements HTTP mechanics.
 *
 * <p>
 * Signature scheme (Sim-compatible): {@code X-ByteChef-Signature: t=<epochMillis>,v1=<hex>} where {@code v1} is
 * HMAC-SHA256 over {@code "<t>.<rawBody>"} with the configured secret. Receivers verify by recomputing over the exact
 * raw body. Every delivery also carries {@code X-ByteChef-Event}, {@code X-ByteChef-Timestamp}, and a random
 * {@code X-ByteChef-Delivery} id for receiver-side idempotency.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
public class WebhookNotificationClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final HttpClient httpClient;

    public WebhookNotificationClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * Delivers the request, throwing {@link WebhookDeliveryException} on transport failure or a non-2xx response and
     * {@link IllegalArgumentException} when the URL fails SSRF validation. Callers decide retry/bookkeeping policy.
     */
    public void deliver(WebhookDeliveryRequest webhookDeliveryRequest) {
        String url = webhookDeliveryRequest.url();

        try {
            UrlValidator.validate(url, Set.of());
        } catch (UrlValidationException urlValidationException) {
            throw new IllegalArgumentException(urlValidationException.getMessage(), urlValidationException);
        }

        long timestamp = System.currentTimeMillis();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-ByteChef-Event", webhookDeliveryRequest.eventType())
            .header("X-ByteChef-Timestamp", String.valueOf(timestamp))
            .header("X-ByteChef-Delivery", String.valueOf(UUID.randomUUID()))
            .POST(HttpRequest.BodyPublishers.ofString(webhookDeliveryRequest.payloadJson()));

        for (Map.Entry<String, String> header : webhookDeliveryRequest.headers()
            .entrySet()) {

            requestBuilder.header(header.getKey(), header.getValue());
        }

        String secret = webhookDeliveryRequest.secret();

        if (secret != null && !secret.isBlank()) {
            requestBuilder.header(
                "X-ByteChef-Signature",
                "t=" + timestamp + ",v1=" + sign(secret, timestamp + "." + webhookDeliveryRequest.payloadJson()));
        }

        try {
            HttpResponse<String> httpResponse = httpClient.send(
                requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            int statusCode = httpResponse.statusCode();

            if (statusCode >= 300) {
                throw new WebhookDeliveryException(
                    "Webhook delivery to " + url + " returned HTTP " + statusCode, statusCode);
            }
        } catch (IOException ioException) {
            throw new WebhookDeliveryException("Failed to deliver webhook to " + url, ioException);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread()
                .interrupt();

            throw new WebhookDeliveryException("Webhook delivery to " + url + " interrupted", interruptedException);
        }
    }

    static String sign(String secret, String signedContent) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);

            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));

            HexFormat hexFormat = HexFormat.of();

            return hexFormat.formatHex(mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Unable to compute webhook signature", exception);
        }
    }
}
