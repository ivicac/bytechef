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

package com.bytechef.component.odoo.action;

import static com.bytechef.component.odoo.constant.OdooConstants.API_KEY;
import static com.bytechef.component.odoo.constant.OdooConstants.DATABASE;
import static com.bytechef.component.odoo.constant.OdooConstants.FIELDS;
import static com.bytechef.component.odoo.constant.OdooConstants.LIMIT;
import static com.bytechef.component.odoo.constant.OdooConstants.MODEL;
import static com.bytechef.component.odoo.constant.OdooConstants.USERNAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.ContextFunction;
import com.bytechef.component.definition.Context.Http;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.Executor;
import com.bytechef.component.definition.Context.Http.Response;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.bytechef.component.test.definition.extension.MockContextSetupExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * @author Ivica Cardic
 */
@ExtendWith(MockContextSetupExtension.class)
class OdooSearchReadRecordsActionTest {

    private final ArgumentCaptor<Body> bodyArgumentCaptor = forClass(Http.Body.class);
    private final Parameters mockedConnectionParameters = MockParametersFactory.create(
        Map.of(DATABASE, "mycompany", USERNAME, "admin@example.com", API_KEY, "api-key"));
    private final Parameters mockedInputParameters = MockParametersFactory.create(
        Map.of(MODEL, "res.partner", FIELDS, List.of("name"), LIMIT, 5));
    private final ArgumentCaptor<String> stringArgumentCaptor = forClass(String.class);

    @Test
    void testPerform(
        Context mockedContext, Response mockedResponse, Executor mockedExecutor, Http mockedHttp,
        ArgumentCaptor<ContextFunction<Http, Executor>> httpFunctionArgumentCaptor) {

        when(mockedHttp.post(stringArgumentCaptor.capture()))
            .thenReturn(mockedExecutor);
        when(mockedExecutor.body(bodyArgumentCaptor.capture()))
            .thenReturn(mockedExecutor);
        when(mockedResponse.getBody())
            .thenReturn(Map.of("result", 2), Map.of("result", List.of(Map.of("id", 1, "name", "Acme"))));

        Object result = OdooSearchReadRecordsAction.perform(
            mockedInputParameters, mockedConnectionParameters, mockedContext);

        assertEquals(Map.of("result", List.of(Map.of("id", 1, "name", "Acme"))), result);
        assertNotNull(httpFunctionArgumentCaptor.getValue());
        assertEquals(List.of("/jsonrpc", "/jsonrpc"), stringArgumentCaptor.getAllValues());

        List<Body> bodies = bodyArgumentCaptor.getAllValues();

        assertEquals(2, bodies.size());

        Body expectedAuthenticateBody = Body.of(
            Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "call",
                "params",
                Map.of(
                    "service", "common",
                    "method", "authenticate",
                    "args", List.of("mycompany", "admin@example.com", "api-key", Map.of()))));

        assertEquals(expectedAuthenticateBody, bodies.get(0));

        Body expectedExecuteBody = Body.of(
            Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "call",
                "params",
                Map.of(
                    "service", "object",
                    "method", "execute_kw",
                    "args",
                    List.of(
                        "mycompany", 2, "api-key", "res.partner", "search_read", List.of(List.of()),
                        Map.of(FIELDS, List.of("name"), LIMIT, 5)))));

        assertEquals(expectedExecuteBody, bodies.get(1));
    }
}
