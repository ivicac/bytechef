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

package com.bytechef.message.broker.amqp.converter;

import com.bytechef.message.broker.serializer.MessageTypeResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.MessageConversionException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves the inbound type-id header of a ByteChef event. {@link DefaultJacksonJavaTypeMapper} trusts packages by
 * exact name only, so listing {@code com.bytechef} would still reject every event class in a sub-package, and trusting
 * {@code *} would let anyone who can publish to the broker name an arbitrary class. Types under the
 * {@link MessageTypeResolver#ALLOWED_PACKAGE} prefix are resolved here — the same allowlist the Redis and Kafka
 * deserializers apply; anything else falls through to the default mapper and its {@code java.util}/{@code java.lang}
 * trust list.
 *
 * @author Ivica Cardic
 */
public class AllowedPackageJacksonJavaTypeMapper extends DefaultJacksonJavaTypeMapper {

    private final ObjectMapper objectMapper;

    @SuppressFBWarnings("EI2")
    public AllowedPackageJacksonJavaTypeMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public JavaType toJavaType(MessageProperties properties) {
        String typeName = retrieveHeaderAsString(properties, getClassIdFieldName());

        if (typeName == null || !MessageTypeResolver.isAllowed(typeName)) {
            return super.toJavaType(properties);
        }

        try {
            return objectMapper.constructType(MessageTypeResolver.resolve(typeName));
        } catch (ClassNotFoundException classNotFoundException) {
            throw new MessageConversionException(
                "Failed to resolve message type " + typeName, classNotFoundException);
        }
    }
}
