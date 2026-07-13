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

package com.bytechef.component.serpapi.action;

import static com.bytechef.component.serpapi.constant.SerpApiConstants.ENGINE;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.GL;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.HL;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.LOCATION;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.NUM;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.Q;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.ContextFunction;
import com.bytechef.component.definition.Context.Http;
import com.bytechef.component.definition.Context.Http.Configuration;
import com.bytechef.component.definition.Context.Http.Configuration.ConfigurationBuilder;
import com.bytechef.component.definition.Context.Http.Executor;
import com.bytechef.component.definition.Context.Http.Response;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.bytechef.component.test.definition.extension.MockContextSetupExtension;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * @author Ivica Cardic
 */
@ExtendWith(MockContextSetupExtension.class)
class SerpApiSearchActionTest {

    private final Parameters mockedParameters = MockParametersFactory.create(
        Map.of(Q, "coffee", LOCATION, "Austin, Texas", GL, "us", HL, "en", NUM, 10));
    private final ArgumentCaptor<Object[]> queryArgumentCaptor = forClass(Object[].class);
    private final ArgumentCaptor<String> stringArgumentCaptor = forClass(String.class);

    @Test
    void testPerform(
        Context mockedContext, Response mockedResponse, Executor mockedExecutor, Http mockedHttp,
        ArgumentCaptor<ContextFunction<Http, Executor>> httpFunctionArgumentCaptor,
        ArgumentCaptor<ConfigurationBuilder> configurationBuilderArgumentCaptor) {

        when(mockedHttp.get(stringArgumentCaptor.capture()))
            .thenReturn(mockedExecutor);
        when(mockedExecutor.queryParameters(queryArgumentCaptor.capture()))
            .thenReturn(mockedExecutor);
        when(mockedResponse.getBody())
            .thenReturn(Map.of("search_metadata", Map.of("status", "Success")));

        Object result = SerpApiSearchAction.perform(mockedParameters, mockedParameters, mockedContext);

        assertEquals(Map.of("search_metadata", Map.of("status", "Success")), result);
        assertNotNull(httpFunctionArgumentCaptor.getValue());
        assertEquals("/search", stringArgumentCaptor.getValue());

        ConfigurationBuilder configurationBuilder = configurationBuilderArgumentCaptor.getValue();
        Configuration configuration = configurationBuilder.build();

        assertEquals(ResponseType.JSON, configuration.getResponseType());

        Object[] expectedQueryParameters = {
            ENGINE, "google",
            Q, "coffee",
            LOCATION, "Austin, Texas",
            GL, "us",
            HL, "en",
            NUM, 10
        };

        assertArrayEquals(expectedQueryParameters, queryArgumentCaptor.getValue());
    }
}
