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

package com.bytechef.platform.webhook.message.route;

import com.bytechef.message.route.MessageRoute;

/**
 * @author Ivica Cardic
 */
public enum SseStreamMessageRoute implements MessageRoute {

    /**
     * Job status and streamed task events, published by the coordinator and worker. A {@code CONTROL} (broadcast)
     * route: the node holding the awaiting future or the live SSE emitter for a job is unknown to the publisher, so
     * every node must see every event and ignore the jobs it does not hold. On a {@code MESSAGE} (work queue) route a
     * second replica would consume the event and the waiting node would time out.
     */
    SSE_STREAM_EVENTS(MessageRoute.Exchange.CONTROL, "sse.sse_stream_events");

    private MessageRoute.Exchange exchange;
    private String routeName;

    SseStreamMessageRoute() {
    }

    SseStreamMessageRoute(MessageRoute.Exchange exchange, String routeName) {
        this.exchange = exchange;
        this.routeName = routeName;
    }

    @Override
    public MessageRoute.Exchange getExchange() {
        return exchange;
    }

    @Override
    public String getName() {
        return routeName;
    }

    @Override
    public boolean isOrdered() {
        return true;
    }

    @Override
    public String toString() {
        return "SseStreamMessageRoute{" +
            "exchange=" + exchange +
            ", routeName='" + routeName + '\'' +
            "} ";
    }
}
