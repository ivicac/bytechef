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

package com.bytechef.platform.webhook.executor.config;

import static com.bytechef.tenant.TenantContext.CURRENT_TENANT_ID;

import com.bytechef.message.broker.config.MessageBrokerConfigurer;
import com.bytechef.platform.webhook.event.SseStreamEvent;
import com.bytechef.platform.webhook.executor.SseStreamBridgeRegistry;
import com.bytechef.platform.webhook.message.route.SseStreamMessageRoute;
import com.bytechef.tenant.TenantContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Consumes the {@code SSE_STREAM_EVENTS} broker route and relays each event to the node-local
 * {@link SseStreamBridgeRegistry}, which holds the live SSE emitters for webhook requests this node is serving. This is
 * the consumer side of the SSE stream, so it must run wherever the webhook HTTP request (and its bridge) lives — not
 * gated on the coordinator, which in a distributed topology is a different process that only publishes onto the route.
 *
 * @author Ivica Cardic
 */
@Configuration
public class SseStreamMessageBrokerConfigurerConfiguration {

    @Bean
    MessageBrokerConfigurer<?> sseStreamMessageBrokerConfigurer(SseStreamBridgeRegistry sseStreamBridgeRegistry) {
        SseStreamDelegate sseStreamDelegate = new SseStreamDelegate(sseStreamBridgeRegistry);

        return (listenerEndpointRegistrar, messageBrokerListenerRegistrar) -> messageBrokerListenerRegistrar
            .registerListenerEndpoint(
                listenerEndpointRegistrar, SseStreamMessageRoute.SSE_STREAM_EVENTS, 1, sseStreamDelegate,
                "onSseStreamEvent");
    }

    private record SseStreamDelegate(SseStreamBridgeRegistry sseStreamBridgeRegistry) {

        @SuppressFBWarnings("UPM")
        public void onSseStreamEvent(SseStreamEvent sseStreamEvent) {
            TenantContext.runWithTenantId(
                (String) sseStreamEvent.getMetadata(CURRENT_TENANT_ID),
                () -> sseStreamBridgeRegistry.onSseStreamEvent(sseStreamEvent));
        }
    }
}
