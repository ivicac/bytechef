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
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.message.broker.redis.serializer.RedisMessageDeserializer;
import com.bytechef.message.route.MessageRoute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * @author Ivica Cardic
 */
class RedisListenerEndpointRegistrarTest {

    private static final String CONSUMER_GROUP = "message_event_group";
    private static final String STREAM_NAME = "test_stream";

    private final List<Object> firstReceivedMessages = new ArrayList<>();
    private final RedisMessageDeserializer redisMessageDeserializer = mock(RedisMessageDeserializer.class);
    private final List<Object> secondReceivedMessages = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);

    @BeforeEach
    void beforeEach() {
        doReturn(streamOperations).when(stringRedisTemplate)
            .opsForStream();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEachStreamRecordIsDeliveredToASingleDelegate() {
        RedisListenerEndpointRegistrar streamRedisListenerEndpointRegistrar = new RedisListenerEndpointRegistrar(
            redisMessageDeserializer, stringRedisTemplate, Runnable::run);

        MessageRoute streamMessageRoute = new TestMessageRoute(MessageRoute.Exchange.MESSAGE, STREAM_NAME);

        streamRedisListenerEndpointRegistrar.registerListenerEndpoint(
            streamMessageRoute, new FirstMessageHandler(), "handle");
        streamRedisListenerEndpointRegistrar.registerListenerEndpoint(
            streamMessageRoute, new SecondMessageHandler(), "handle");

        when(redisMessageDeserializer.deserialize("raw-message-1")).thenReturn("payload-1");
        when(redisMessageDeserializer.deserialize("raw-message-2")).thenReturn("payload-2");

        List<MapRecord<String, Object, Object>> records = List.of(
            createStreamRecord("1-0", "raw-message-1"), createStreamRecord("2-0", "raw-message-2"));

        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
            .thenReturn(records)
            .thenAnswer(invocation -> {
                streamRedisListenerEndpointRegistrar.stop();

                return List.of();
            });

        streamRedisListenerEndpointRegistrar.start();

        assertThat(firstReceivedMessages).containsExactly("payload-1");
        assertThat(secondReceivedMessages).containsExactly("payload-2");

        verify(streamOperations).acknowledge(STREAM_NAME, CONSUMER_GROUP, RecordId.of("1-0"));
        verify(streamOperations).acknowledge(STREAM_NAME, CONSUMER_GROUP, RecordId.of("2-0"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPollingBacksOffAfterRepeatedReadFailures() throws InterruptedException {
        RedisListenerEndpointRegistrar failingRedisListenerEndpointRegistrar = new RedisListenerEndpointRegistrar(
            redisMessageDeserializer, stringRedisTemplate, runnable -> new Thread(runnable).start());

        failingRedisListenerEndpointRegistrar.registerListenerEndpoint(
            new TestMessageRoute(MessageRoute.Exchange.MESSAGE, STREAM_NAME), new FirstMessageHandler(), "handle");

        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
            .thenThrow(new IllegalStateException("LettuceConnectionFactory has been STOPPED"));

        failingRedisListenerEndpointRegistrar.start();

        Thread.sleep(350);

        failingRedisListenerEndpointRegistrar.stop();

        verify(streamOperations, atMost(5)).read(
            any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));
    }

    @Test
    void testStalePendingMessagesAreReclaimedRedeliveredAndAcknowledged() {
        RedisListenerEndpointRegistrar reclaimingRedisListenerEndpointRegistrar = new RedisListenerEndpointRegistrar(
            redisMessageDeserializer, stringRedisTemplate, Runnable::run);

        reclaimingRedisListenerEndpointRegistrar.registerListenerEndpoint(
            new TestMessageRoute(MessageRoute.Exchange.MESSAGE, STREAM_NAME), new FirstMessageHandler(), "handle");

        PendingMessage stalePendingMessage = new PendingMessage(
            RecordId.of("1-1"), Consumer.from(CONSUMER_GROUP, "dead-consumer"), Duration.ofMinutes(5), 1);
        PendingMessage freshPendingMessage = new PendingMessage(
            RecordId.of("1-2"), Consumer.from(CONSUMER_GROUP, "live-consumer"), Duration.ofSeconds(1), 1);

        when(streamOperations.pending(eq(STREAM_NAME), eq(CONSUMER_GROUP), any(), eq(100L)))
            .thenReturn(new PendingMessages(CONSUMER_GROUP, List.of(stalePendingMessage, freshPendingMessage)));
        when(streamOperations.claim(
            eq(STREAM_NAME), eq(CONSUMER_GROUP), anyString(), any(Duration.class), any(RecordId[].class)))
                .thenReturn(List.of(createStreamRecord("1-1", "raw-message")));
        when(redisMessageDeserializer.deserialize("raw-message")).thenReturn("deserialized-payload");

        reclaimingRedisListenerEndpointRegistrar.reclaimAbandonedPendingMessages();

        assertThat(firstReceivedMessages).containsExactly("deserialized-payload");

        verify(streamOperations).claim(
            STREAM_NAME, CONSUMER_GROUP, reclaimingRedisListenerEndpointRegistrar.toString(), Duration.ofSeconds(60),
            RecordId.of("1-1"));
        verify(streamOperations).acknowledge(STREAM_NAME, CONSUMER_GROUP, RecordId.of("1-1"));
    }

    @Test
    void testFreshPendingMessagesAreNotClaimed() {
        RedisListenerEndpointRegistrar reclaimingRedisListenerEndpointRegistrar = new RedisListenerEndpointRegistrar(
            redisMessageDeserializer, stringRedisTemplate, Runnable::run);

        reclaimingRedisListenerEndpointRegistrar.registerListenerEndpoint(
            new TestMessageRoute(MessageRoute.Exchange.MESSAGE, STREAM_NAME), new FirstMessageHandler(), "handle");

        PendingMessage freshPendingMessage = new PendingMessage(
            RecordId.of("2-1"), Consumer.from(CONSUMER_GROUP, "live-consumer"), Duration.ofSeconds(5), 1);

        when(streamOperations.pending(eq(STREAM_NAME), eq(CONSUMER_GROUP), any(), eq(100L)))
            .thenReturn(new PendingMessages(CONSUMER_GROUP, List.of(freshPendingMessage)));

        reclaimingRedisListenerEndpointRegistrar.reclaimAbandonedPendingMessages();

        assertThat(firstReceivedMessages).isEmpty();

        verify(streamOperations, never()).claim(
            anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }

    @Test
    void testEmptyPendingListIsANoOp() {
        RedisListenerEndpointRegistrar reclaimingRedisListenerEndpointRegistrar = new RedisListenerEndpointRegistrar(
            redisMessageDeserializer, stringRedisTemplate, Runnable::run);

        reclaimingRedisListenerEndpointRegistrar.registerListenerEndpoint(
            new TestMessageRoute(MessageRoute.Exchange.MESSAGE, STREAM_NAME), new FirstMessageHandler(), "handle");

        when(streamOperations.pending(eq(STREAM_NAME), eq(CONSUMER_GROUP), any(), eq(100L)))
            .thenReturn(new PendingMessages(CONSUMER_GROUP, List.of()));

        reclaimingRedisListenerEndpointRegistrar.reclaimAbandonedPendingMessages();

        assertThat(firstReceivedMessages).isEmpty();

        verify(streamOperations, never()).claim(
            anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }

    private static MapRecord<String, Object, Object> createStreamRecord(String recordId, String message) {
        MapRecord<String, Object, Object> mapRecord = MapRecord.create(STREAM_NAME, Map.of("message", message));

        return mapRecord.withId(RecordId.of(recordId));
    }

    public class FirstMessageHandler {

        public void handle(String message) {
            firstReceivedMessages.add(message);
        }
    }

    public class SecondMessageHandler {

        public void handle(String message) {
            secondReceivedMessages.add(message);
        }
    }

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
}
