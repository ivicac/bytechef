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

package com.bytechef.platform.webhook.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.commons.util.EncodingUtils;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade.JobResumeOutcome;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

/**
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class SlackInteractivityHandlerTest {

    private static final String SIGNING_SECRET = "test-signing-secret";

    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final JobResumeFacade jobResumeFacade = mock(JobResumeFacade.class);

    private SlackInteractivityHandler slackInteractivityHandler;

    @BeforeEach
    void setUp() throws Exception {
        // The message rewrite through response_url is best-effort; the mock RestClient throws on use in this test
        // setup, exercising exactly that tolerance.
        slackInteractivityHandler = new SlackInteractivityHandler(
            connectionService, jobResumeFacade, RestClient.create("http://localhost:1"));

        Connection connection = new Connection();

        connection.setParameters(Map.of("signingSecret", SIGNING_SECRET));

        when(connectionService.getConnections(eq("slack"), anyInt(), any())).thenReturn(List.of(connection));
    }

    @Test
    void testVerifiedApproveCallbackResolvesTheApproval() throws Exception {
        String rawBody = rawBody(SlackInteractivityHandler.ACTION_APPROVE);
        String timestamp = String.valueOf(Instant.now()
            .getEpochSecond());

        when(jobResumeFacade.resumeJob(anyString(), eq(Map.of("approved", true)), eq("@jane")))
            .thenReturn(JobResumeOutcome.OK);

        SlackInteractivityHandler.Result result = slackInteractivityHandler.handle(
            rawBody, timestamp, sign(timestamp, rawBody, SIGNING_SECRET));

        assertEquals(SlackInteractivityHandler.Result.HANDLED, result);

        // The Slack user is signature-verified, so it flows through as the trusted resolver identity.
        verify(jobResumeFacade).resumeJob(anyString(), eq(Map.of("approved", true)), eq("@jane"));
    }

    @Test
    void testInvalidSignatureIsRejectedWithoutActing() throws Exception {
        String rawBody = rawBody(SlackInteractivityHandler.ACTION_DISCARD);
        String timestamp = String.valueOf(Instant.now()
            .getEpochSecond());

        SlackInteractivityHandler.Result result = slackInteractivityHandler.handle(
            rawBody, timestamp, sign(timestamp, rawBody, "wrong-secret"));

        assertEquals(SlackInteractivityHandler.Result.UNAUTHORIZED, result);

        verify(jobResumeFacade, never()).resumeJob(anyString(), any(), any());
    }

    @Test
    void testStaleTimestampIsRejectedWithoutActing() throws Exception {
        String rawBody = rawBody(SlackInteractivityHandler.ACTION_APPROVE);
        String timestamp = String.valueOf(Instant.now()
            .getEpochSecond() - 3600);

        SlackInteractivityHandler.Result result = slackInteractivityHandler.handle(
            rawBody, timestamp, sign(timestamp, rawBody, SIGNING_SECRET));

        assertEquals(SlackInteractivityHandler.Result.UNAUTHORIZED, result);

        verify(jobResumeFacade, never()).resumeJob(anyString(), any(), any());
    }

    @Test
    void testUnrelatedActionIsIgnored() {
        String rawBody = rawBody("some_other_action");
        String timestamp = String.valueOf(Instant.now()
            .getEpochSecond());

        SlackInteractivityHandler.Result result = slackInteractivityHandler.handle(rawBody, timestamp, "v0=whatever");

        assertEquals(SlackInteractivityHandler.Result.IGNORED, result);

        verify(jobResumeFacade, never()).resumeJob(anyString(), any(), any());
    }

    private static String rawBody(String actionId) {
        // A JobResumeId is base64("tenantId:jobId:uuid"); the handler parses it to anchor the tenant.
        String resumeId = EncodingUtils.base64EncodeToString(
            "public:42:123e4567-e89b-12d3-a456-426614174000".getBytes(StandardCharsets.UTF_8));

        String payload = """
            {"type":"block_actions","user":{"username":"jane"},"response_url":"https://hooks.slack.invalid/actions/x",
             "actions":[{"action_id":"%s","value":"%s"}]}""".formatted(actionId, resumeId);

        return "payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8);
    }

    private static String sign(String timestamp, String rawBody, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");

        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        HexFormat hexFormat = HexFormat.of();

        return "v0=" + hexFormat.formatHex(
            mac.doFinal(("v0:" + timestamp + ":" + rawBody).getBytes(StandardCharsets.UTF_8)));
    }
}
