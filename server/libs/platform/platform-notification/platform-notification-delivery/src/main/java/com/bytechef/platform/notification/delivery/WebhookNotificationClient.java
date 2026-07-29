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
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriTemplateHandler;

/**
 * The single outbound-webhook transport for every surface that posts webhooks — platform notifications (job-status
 * channel), the Atlas per-job callback webhooks ({@code WebhookJobStatusApplicationEventListener} delegates here), the
 * EE AI-observability alert channels, and Slack (via {@code SlackNotificationClient}). One {@code RestTemplate}, one
 * retry mechanism (Spring core {@code RetryTemplate} + {@code ExponentialBackOff} — the mechanics that previously lived
 * inline in the job-status listener), one place for SSRF validation, standard headers, and HMAC signing.
 *
 * <p>
 * Signature scheme (Sim-compatible): {@code X-ByteChef-Signature: t=<epochMillis>,v1=<hex>} where {@code v1} is
 * HMAC-SHA256 over {@code "<t>.<rawBody>"} with the configured secret. Every string-payload delivery also carries
 * {@code X-ByteChef-Event}, {@code X-ByteChef-Timestamp}, and a random {@code X-ByteChef-Delivery} id for receiver-side
 * idempotency.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
public class WebhookNotificationClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int MAX_BODY_BYTES = 4000;
    private static final int MAX_BODY_CHARACTERS = 1000;

    private final RestTemplate restTemplate;

    public WebhookNotificationClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);

        this.restTemplate = new RestTemplate(requestFactory);

    /**
     * Retry schedule for a delivery. {@code null} anywhere means the single-attempt default; use {@link #none()} for an
     * explicit single attempt.
     */
    public record WebhookRetry(int maxRetries, Duration initialInterval, double multiplier) {

            @Override
            public boolean hasError(ClientHttpResponse clientHttpResponse) throws IOException {
                HttpStatusCode statusCode = clientHttpResponse.getStatusCode();

    /**
     * Delivers an admin-configured notification webhook: SSRF-validated, standard headers, optional HMAC signature,
     * single attempt. Throws {@link WebhookDeliveryException} on transport failure or a non-2xx response and
     * {@link IllegalArgumentException} when the URL fails validation. Callers decide bookkeeping policy.
     */
    public void deliver(WebhookDeliveryRequest webhookDeliveryRequest) {
        deliver(webhookDeliveryRequest, WebhookRetry.none());
    }

    /**
     * Same as {@link #deliver(WebhookDeliveryRequest)} with a retry schedule; each attempt reposts the identical signed
     * body.
     */
    public void deliver(WebhookDeliveryRequest webhookDeliveryRequest, WebhookRetry webhookRetry) {
        String url = webhookDeliveryRequest.url();

        try {
            UrlValidator.validate(url, Set.of());
        } catch (UrlValidationException urlValidationException) {
            throw new IllegalArgumentException(urlValidationException.getMessage(), urlValidationException);
        }

                String responseBody = readBody(clientHttpResponse);

                throw new RestClientResponseException(
                    "HTTP " + statusCode.value(), statusCode, clientHttpResponse.getStatusText(),
                    clientHttpResponse.getHeaders(), responseBody == null ? null : responseBody.getBytes(
                        StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
            }
        });
    }

    public WebhookResponse post(WebhookDelivery webhookDelivery, WebhookPayload webhookPayload) {
        URI uri = toUri(restTemplate, webhookDelivery.url());
        String host = uri.getHost();
        String payloadJson = webhookPayload.json();

        HttpHeaders httpHeaders = new HttpHeaders();

        httpHeaders.set(HttpHeaders.ACCEPT, DEFAULT_ACCEPT_HEADER);

        for (Map.Entry<String, String> header : webhookDelivery.headers()
            .entrySet()) {

            if (!WebhookReservedHeaders.isReserved(header.getKey())) {
                httpHeaders.set(header.getKey(), header.getValue());
            }
        }

        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        for (Map.Entry<String, String> header : webhookDeliveryRequest.headers()
            .entrySet()) {

            httpHeaders.set(header.getKey(), header.getValue());
        }

        String secret = webhookDelivery.secret();

        if (secret != null && !secret.isBlank()) {
            httpHeaders.set(
                "X-ByteChef-Signature", "t=" + timestamp + ",v1=" + sign(secret, timestamp + "." + payloadJson));
        }

        post(url, new HttpEntity<>(webhookDeliveryRequest.payloadJson(), httpHeaders), webhookRetry);
    }

    /**
     * Delivers a caller-registered callback webhook (Atlas per-job {@code Job.Webhook} entries): the payload object is
     * serialized by the message converters exactly as the pre-consolidation listener did, and the URL is NOT
     * SSRF-validated — job callbacks are registered by authenticated API callers and may legitimately target internal
     * hosts in self-hosted deployments (pre-existing contract).
     */
    public void deliverEvent(String url, Object payload, WebhookRetry webhookRetry) {
        post(url, new HttpEntity<>(payload), webhookRetry);
    }

    private void post(String url, HttpEntity<?> httpEntity, WebhookRetry webhookRetry) {
        try {
            if (webhookRetry.maxRetries() <= 0) {
                exchange(url, httpEntity);
            } else {
                RetryTemplate retryTemplate = new RetryTemplate(
                    RetryPolicy.builder()
                        .backOff(
                            new ExponentialBackOff(
                                webhookRetry.initialInterval()
                                    .toMillis(),
                                webhookRetry.multiplier()))
                        .maxRetries(webhookRetry.maxRetries())
                        .build());

                retryTemplate.execute(() -> {
                    exchange(url, httpEntity);

                    return null;
                });
            }
        } catch (RetryException retryException) {
            throw new WebhookDeliveryException("Webhook delivery to " + url + " failed after retries", retryException);
        } catch (RestClientResponseException restClientResponseException) {
            int statusCode = restClientResponseException.getStatusCode()
                .value();

            String responseBody = restClientResponseException.getResponseBodyAsByteArray().length == 0
                ? null : restClientResponseException.getResponseBodyAsString(StandardCharsets.UTF_8);

            throw new WebhookDeliveryException(
                "Webhook delivery to " + host + " returned HTTP " + statusCode, statusCode,
                restClientResponseException, responseBody);
        } catch (RestClientException restClientException) {
            throw new WebhookDeliveryException("Failed to deliver webhook to " + host, null, restClientException);
        }
    }

    private static @Nullable String readBody(ClientHttpResponse clientHttpResponse) throws IOException {
        byte[] bodyBytes;

        try (InputStream inputStream = clientHttpResponse.getBody()) {
            bodyBytes = inputStream.readNBytes(MAX_BODY_BYTES);
        }

        if (bodyBytes.length == 0) {
            return null;
        }

        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        if (body.length() <= MAX_BODY_CHARACTERS) {
            return body;
        }

        int end = MAX_BODY_CHARACTERS;

        if (Character.isHighSurrogate(body.charAt(end - 1))) {
            end--;
        }

        return body.substring(0, end) + "…";
    }

    private static URI toUri(RestTemplate restTemplate, String url) {
        UriTemplateHandler uriTemplateHandler = restTemplate.getUriTemplateHandler();

        URI uri;

        try {
            uri = uriTemplateHandler.expand(url);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new InvalidWebhookUrlException("Webhook URL is not a valid URI", exception);
        }

        String scheme = uri.getScheme();

        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new InvalidWebhookUrlException("Webhook URL must use http or https", null);
        }

        if (uri.getHost() == null) {
            throw new InvalidWebhookUrlException("Webhook URL has no host", null);
        }

        return uri;
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

    public record WebhookResponse(int statusCode, @Nullable String body) {
    }
}
