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

package com.bytechef.test.voice;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.component.definition.ActionDefinition.WebSocketHandler.WebSocketEmitter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A {@link WebSocketEmitter} the contract test can both feed and inspect: {@code send}/{@code sendBinary} calls the
 * provider makes on it are recorded as {@code events}/{@code audio}, and {@code dispatchMessage}/
 * {@code dispatchBinaryMessage} play the browser's side of the conversation back into the listeners the provider
 * registered.
 *
 * @author Ivica Cardic
 */
public final class RecordingWebSocketEmitter implements WebSocketEmitter {

    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private final List<byte[]> audio = new CopyOnWriteArrayList<>();
    private final List<Consumer<Object>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<byte[]>> binaryMessageListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> timeoutListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicReference<Throwable> firstError = new AtomicReference<>();

    public List<Map<String, Object>> events() {
        return List.copyOf(events);
    }

    public List<byte[]> audio() {
        return List.copyOf(audio);
    }

    public boolean completed() {
        return completed.get();
    }

    public Throwable error() {
        return firstError.get();
    }

    public void dispatchMessage(Object data) {
        messageListeners.forEach(messageListener -> messageListener.accept(data));
    }

    public void dispatchBinaryMessage(byte[] data) {
        binaryMessageListeners.forEach(binaryMessageListener -> binaryMessageListener.accept(data));
    }

    @Override
    public void addBinaryMessageListener(Consumer<byte[]> binaryMessageListener) {
        binaryMessageListeners.add(binaryMessageListener);
    }

    @Override
    public void addCloseListener(Runnable closeListener) {
        closeListeners.add(closeListener);
    }

    @Override
    public void addMessageListener(Consumer<Object> messageListener) {
        messageListeners.add(messageListener);
    }

    @Override
    public void addTimeoutListener(Runnable timeoutListener) {
        timeoutListeners.add(timeoutListener);
    }

    @Override
    public void complete() {
        if (completed.compareAndSet(false, true)) {
            closeListeners.forEach(Runnable::run);
        }
    }

    @Override
    public void error(Throwable throwable) {
        firstError.compareAndSet(null, throwable);

        complete();
    }

    /**
     * Records every payload as an event map, whatever shape the provider sent: a JSON string or a map as-is, any other
     * object through its JSON form. A payload whose JSON is not an object is recorded under {@code value}, so a test
     * sees it instead of the emitter throwing a {@link ClassCastException} mid-provider.
     */
    @Override
    public void send(Object data) {
        Object value = data instanceof String text ? JsonUtils.read(text) : data;

        if (!(value instanceof Map<?, ?>) && value != null) {
            value = JsonUtils.read(JsonUtils.write(value));
        }

        events.add(toEvent(value));
    }

    private static Map<String, Object> toEvent(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            Map<String, Object> event = new LinkedHashMap<>();

            event.put("value", value);

            return event;
        }

        Map<String, Object> event = new LinkedHashMap<>();

        map.forEach((key, entryValue) -> event.put(String.valueOf(key), entryValue));

        return event;
    }

    @Override
    public void sendBinary(byte[] data) {
        audio.add(data);
    }
}
