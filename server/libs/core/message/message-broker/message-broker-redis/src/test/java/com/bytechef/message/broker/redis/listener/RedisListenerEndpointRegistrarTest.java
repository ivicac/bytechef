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

package com.bytechef.message.broker.redis.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.message.broker.redis.serializer.RedisMessageDeserializer;
import com.bytechef.message.route.MessageRoute;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Pins the registrar's dispatch rules without a Redis: the pending-entry reclaim semantics (stream entries left
 * unacknowledged by a crashed consumer are claimed, redelivered and acknowledged; fresh in-flight entries of live
 * consumers are left alone), that every delegate registered for a route receives every message, and that a
 * {@code CONTROL} route is a pub/sub channel — its delegates are recorded for {@code onMessage} and no consumer group
 * is created for it. Delivery through a live Redis is covered by {@code RedisMessageBrokerIntTest}.
 *
 * @author Ivica Cardic
 */
class RedisListenerEndpointRegistrarTest {

    private static final String QUEUE_NAME = "test_queue";
    private static final String CONSUMER_GROUP = "message_event_group";

    private final RedisMessageDeserializer redisMessageDeserializer = mock(RedisMessageDeserializer.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);

    private final List<Object> receivedMessages = new ArrayList<>();
    private final List<Object> secondReceivedMessages = new ArrayList<>();

    private RedisListenerEndpointRegistrar redisListenerEndpointRegistrar;

    public class TestMessageHandler {

        public void handle(String message) {
            receivedMessages.add(message);
        }
    }

    public class SecondTestMessageHandler {

        public void handle(String message) {
            secondReceivedMessages.add(message);
        }
    }

    @BeforeEach
    void beforeEach() {
        doReturn(streamOperations).when(stringRedisTemplate)
            .opsForStream();

        redisListenerEndpointRegistrar = new RedisListenerEndpointRegistrar(
            mock(RedisConnectionFactory.class), redisMessageDeserializer, stringRedisTemplate);

        redisListenerEndpointRegistrar.registerListenerEndpoint(
            messageRoute(QUEUE_NAME, MessageRoute.Exchange.MESSAGE), new TestMessageHandler(), "handle");
    }

    @Test
    void testEveryDelegateRegisteredForAStreamRouteReceivesAReclaimedMessage() {
        redisListenerEndpointRegistrar.registerListenerEndpoint(
            messageRoute(QUEUE_NAME, MessageRoute.Exchange.MESSAGE), new SecondTestMessageHandler(), "handle");

        PendingMessage stalePendingMessage = new PendingMessage(
            RecordId.of("3-1"), Consumer.from(CONSUMER_GROUP, "dead-consumer"), Duration.ofMinutes(5), 1);

        when(streamOperations.pending(eq(QUEUE_NAME), eq(CONSUMER_GROUP), any(), eq(100L)))
            .thenReturn(new PendingMessages(CONSUMER_GROUP, List.of(stalePendingMessage)));

        MapRecord<String, Object, Object> claimedRecord = StreamRecords.newRecord()
            .in(QUEUE_NAME)
            .withId(RecordId.of("3-1"))
            .ofMap(Map.of("message", "raw-message"));

        when(streamOperations.claim(
            eq(QUEUE_NAME), eq(CONSUMER_GROUP), anyString(), any(Duration.class), any(RecordId[].class)))
                .thenReturn(List.of(claimedRecord));
        when(redisMessageDeserializer.deserialize("raw-message")).thenReturn("deserialized-payload");

        redisListenerEndpointRegistrar.reclaimAbandonedPendingMessages();

        assertThat(receivedMessages).containsExactly("deserialized-payload");
        assertThat(secondReceivedMessages).containsExactly("deserialized-payload");

        verify(streamOperations).acknowledge(QUEUE_NAME, CONSUMER_GROUP, RecordId.of("3-1"));
    }

    @Test
    void testControlRouteIsAPubSubChannelFannedOutToEveryDelegate() {
        String channelName = "test_channel";

        redisListenerEndpointRegistrar.registerListenerEndpoint(
            messageRoute(channelName, MessageRoute.Exchange.CONTROL), new TestMessageHandler(), "handle");
        redisListenerEndpointRegistrar.registerListenerEndpoint(
            messageRoute(channelName, MessageRoute.Exchange.CONTROL), new SecondTestMessageHandler(), "handle");

        when(redisMessageDeserializer.deserialize("raw-message")).thenReturn("deserialized-payload");

        redisListenerEndpointRegistrar.onMessage(
            new DefaultMessage(
                channelName.getBytes(StandardCharsets.UTF_8), "raw-message".getBytes(StandardCharsets.UTF_8)),
            null);

        assertThat(receivedMessages).containsExactly("deserialized-payload");
        assertThat(secondReceivedMessages).containsExactly("deserialized-payload");

        verify(streamOperations, never()).createGroup(channelName, CONSUMER_GROUP);
    }

    @Test
    void testPubSubMessageOnAChannelWithoutDelegatesIsDropped() {
        redisListenerEndpointRegistrar.onMessage(
            new DefaultMessage(
                "unknown_channel".getBytes(StandardCharsets.UTF_8), "raw-message".getBytes(StandardCharsets.UTF_8)),
            null);

        assertThat(receivedMessages).isEmpty();
    }

    private static MessageRoute messageRoute(String name, MessageRoute.Exchange exchange) {
        return new TestMessageRoute(exchange, name);
    }

    /**
     * A real implementation rather than a mock: the exchange checks are interface default methods, which a Mockito mock
     * answers with {@code false}.
     */
    private record TestMessageRoute(Exchange exchange, String routeName) implements MessageRoute {

        @Override
        public Exchange getExchange() {
            return exchange;
        }

        @Override
        public String getName() {
            return routeName;
        }
    }

    @Test
    void testStalePendingMessagesAreReclaimedRedeliveredAndAcknowledged() {
        PendingMessage stalePendingMessage = new PendingMessage(
            RecordId.of("1-1"), Consumer.from(CONSUMER_GROUP, "dead-consumer"), Duration.ofMinutes(5), 1);
        PendingMessage freshPendingMessage = new PendingMessage(
            RecordId.of("1-2"), Consumer.from(CONSUMER_GROUP, "live-consumer"), Duration.ofSeconds(1), 1);

        when(streamOperations.pending(eq(QUEUE_NAME), eq(CONSUMER_GROUP), any(), eq(100L)))
            .thenReturn(new PendingMessages(CONSUMER_GROUP, List.of(stalePendingMessage, freshPendingMessage)));

        MapRecord<String, Object, Object> claimedRecord = StreamRecords.newRecord()
            .in(QUEUE_NAME)
            .withId(RecordId.of("1-1"))
            .ofMap(Map.of("message", "raw-message"));

        when(streamOperations.claim(
            eq(QUEUE_NAME), eq(CONSUMER_GROUP), anyString(), any(Duration.class), any(RecordId[].class)))
                .thenReturn(List.of(claimedRecord));

        when(redisMessageDeserializer.deserialize("raw-message")).thenReturn("deserialized-payload");

        redisListenerEndpointRegistrar.reclaimAbandonedPendingMessages();

        assertThat(receivedMessages).containsExactly("deserialized-payload");

        verify(streamOperations).acknowledge(QUEUE_NAME, CONSUMER_GROUP, RecordId.of("1-1"));
    }

    @Test
    void testFreshPendingMessagesAreNotClaimed() {
        PendingMessage freshPendingMessage = new PendingMessage(
            RecordId.of("2-1"), Consumer.from(CONSUMER_GROUP, "live-consumer"), Duration.ofSeconds(5), 1);

        when(streamOperations.pending(eq(QUEUE_NAME), eq(CONSUMER_GROUP), any(), eq(100L)))
            .thenReturn(new PendingMessages(CONSUMER_GROUP, List.of(freshPendingMessage)));

        redisListenerEndpointRegistrar.reclaimAbandonedPendingMessages();

        assertThat(receivedMessages).isEmpty();

        verify(streamOperations, never()).claim(
            anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }

    @Test
    void testEmptyPendingListIsANoOp() {
        when(streamOperations.pending(eq(QUEUE_NAME), eq(CONSUMER_GROUP), any(), eq(100L)))
            .thenReturn(new PendingMessages(CONSUMER_GROUP, List.of()));

        redisListenerEndpointRegistrar.reclaimAbandonedPendingMessages();

        assertThat(receivedMessages).isEmpty();

        verify(streamOperations, never()).claim(
            anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }
}
