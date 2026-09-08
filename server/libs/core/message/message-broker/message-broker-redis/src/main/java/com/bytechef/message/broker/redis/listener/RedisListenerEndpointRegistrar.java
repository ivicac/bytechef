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

import com.bytechef.message.broker.redis.serializer.RedisMessageDeserializer;
import com.bytechef.message.route.MessageRoute;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.util.MethodInvoker;

/**
 * Delivers Redis broker messages to the registered listener delegates. A route lands in one of two places depending on
 * its exchange:
 *
 * <ul>
 * <li>{@code MESSAGE} routes are Redis streams read through a shared consumer group, so every message is processed by
 * exactly one instance (competing consumers). Each stream is served by its own {@link StreamMessageListenerContainer}
 * subscription, which issues blocking {@code XREADGROUP} calls on a dedicated connection — a message is dispatched as
 * soon as it lands instead of on the next tick of a polling loop.</li>
 * <li>{@code CONTROL} routes are Redis pub/sub channels, so every message reaches every instance that subscribed. The
 * subscription itself is owned by the configuration's {@code RedisMessageListenerContainer}; this class is the channel
 * {@link MessageListener} that fans each delivery out to the delegates registered for it.</li>
 * </ul>
 *
 * Within one JVM every delegate registered for a route receives every message the JVM receives, matching the in-memory
 * broker.
 *
 * @author Ivica Cardic
 */
public class RedisListenerEndpointRegistrar implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisListenerEndpointRegistrar.class);

    private static final String CONSUMER_GROUP = "message_event_group";
    private static final String MESSAGE_FIELD = "message";

    /**
     * How long a blocking stream read waits for an entry before the subscription loops and checks whether it was
     * cancelled. Bounds shutdown latency only; delivery latency is unaffected because an entry that arrives during the
     * block returns immediately.
     */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);

    private static final int STREAM_READ_BATCH_SIZE = 10;

    private final String consumerName;
    private final RedisConnectionFactory redisConnectionFactory;
    private final RedisMessageDeserializer redisMessageDeserializer;
    private volatile boolean stopped;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamMessageListenerContainer;
    private final Map<String, List<Consumer<String>>> streamInvokersMap = new LinkedHashMap<>();
    private final StringRedisTemplate stringRedisTemplate;
    private final Map<String, List<Consumer<String>>> topicInvokersMap = new HashMap<>();

    @SuppressFBWarnings("EI2")
    public RedisListenerEndpointRegistrar(
        RedisConnectionFactory redisConnectionFactory, RedisMessageDeserializer redisMessageDeserializer,
        StringRedisTemplate stringRedisTemplate) {

        this.consumerName = "consumer-" + Integer.toHexString(System.identityHashCode(this));
        this.redisConnectionFactory = redisConnectionFactory;
        this.redisMessageDeserializer = redisMessageDeserializer;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Pub/sub delivery for a {@code CONTROL} route: fan the message out to every delegate registered for the channel.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channelName = new String(message.getChannel(), StandardCharsets.UTF_8);

        List<Consumer<String>> invokers = topicInvokersMap.get(channelName);

        if (invokers == null) {
            log.warn("No message listeners registered for channel='{}'", channelName);

            return;
        }

        dispatch(invokers, new String(message.getBody(), StandardCharsets.UTF_8));
    }

    public void registerListenerEndpoint(MessageRoute messageRoute, Object delegate, String methodName) {
        String routeName = messageRoute.getName();

        Consumer<String> invoker = (String message) -> invoke(delegate, methodName, message);

        if (messageRoute.isControlExchange()) {
            List<Consumer<String>> invokers = topicInvokersMap.computeIfAbsent(routeName, key -> new ArrayList<>());

            invokers.add(invoker);

            return;
        }

        List<Consumer<String>> invokers = streamInvokersMap.computeIfAbsent(routeName, key -> new ArrayList<>());

        invokers.add(invoker);

        try {
            stringRedisTemplate.opsForStream()
                .createGroup(routeName, CONSUMER_GROUP);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Consumer group already exists or error occurred: {}", e.getMessage());
            }
        }
    }

    public void start() {
        stopped = false;

        if (streamInvokersMap.isEmpty()) {
            return;
        }

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
            StreamMessageListenerContainerOptions.builder()
                .pollTimeout(POLL_TIMEOUT)
                .batchSize(STREAM_READ_BATCH_SIZE)
                .executor(new SimpleAsyncTaskExecutor("redis-stream-listener-"))
                .errorHandler(this::handleStreamError)
                .build();

        streamMessageListenerContainer = StreamMessageListenerContainer.create(redisConnectionFactory, options);

        for (Map.Entry<String, List<Consumer<String>>> entry : streamInvokersMap.entrySet()) {
            String streamName = entry.getKey();
            List<Consumer<String>> invokers = entry.getValue();

            StreamReadRequest<String> streamReadRequest = StreamReadRequest
                .builder(StreamOffset.create(streamName, ReadOffset.lastConsumed()))
                .consumer(org.springframework.data.redis.connection.stream.Consumer.from(CONSUMER_GROUP, consumerName))
                .autoAcknowledge(false)
                .cancelOnError(throwable -> false)
                .errorHandler(this::handleStreamError)
                .build();

            streamMessageListenerContainer.register(streamReadRequest, record -> receive(streamName, invokers, record));
        }

        streamMessageListenerContainer.start();
    }

    public void stop() {
        stopped = true;

        if (streamMessageListenerContainer != null) {
            streamMessageListenerContainer.stop();
        }
    }

    private void receive(String streamName, List<Consumer<String>> invokers, MapRecord<String, String, String> record) {
        Map<String, String> value = record.getValue();

        dispatch(invokers, value.get(MESSAGE_FIELD));

        stringRedisTemplate.opsForStream()
            .acknowledge(streamName, CONSUMER_GROUP, record.getId());
    }

    private void dispatch(List<Consumer<String>> invokers, String message) {
        for (Consumer<String> invoker : invokers) {
            invoker.accept(message);
        }
    }

    private void handleStreamError(Throwable throwable) {
        if (!stopped) {
            log.error(throwable.getMessage(), throwable);
        }
    }

    private void invoke(Object delegate, String methodName, String messageString) {
        try {
            Object message = redisMessageDeserializer.deserialize(messageString);

            MethodInvoker methodInvoker = new MethodInvoker();

            methodInvoker.setTargetObject(delegate);
            methodInvoker.setTargetMethod(methodName);
            methodInvoker.setArguments(message);

            methodInvoker.prepare();

            methodInvoker.invoke();
        } catch (Exception e) {
            if (!stopped) {
                log.error(e.getMessage(), e);
            }
        }
    }
}
