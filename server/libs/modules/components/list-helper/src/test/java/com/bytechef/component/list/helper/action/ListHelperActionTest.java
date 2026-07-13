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

package com.bytechef.component.list.helper.action;

import static com.bytechef.component.list.helper.constant.ListHelperConstants.LIST;
import static com.bytechef.component.list.helper.constant.ListHelperConstants.ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class ListHelperActionTest {

    private final Context mockedContext = mock(Context.class);

    @Test
    void testPerformAverage() {
        Parameters parameters = MockParametersFactory.create(Map.of(LIST, List.of(1, 2, 3)));

        assertEquals(2.0, ListHelperAverageAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformAverageEmptyList() {
        Parameters parameters = MockParametersFactory.create(Map.of(LIST, List.of()));

        assertEquals(0.0, ListHelperAverageAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformCount() {
        Parameters parameters = MockParametersFactory.create(Map.of(LIST, List.of("a", "b", "c")));

        assertEquals(3, ListHelperCountAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformDeduplicate() {
        Parameters parameters = MockParametersFactory.create(Map.of(LIST, List.of("a", "b", "a", "c", "b")));

        assertEquals(List.of("a", "b", "c"), ListHelperDeduplicateAction.perform(parameters, parameters,
            mockedContext));
    }

    @Test
    void testPerformFlatten() {
        Parameters parameters = MockParametersFactory.create(
            Map.of(LIST, List.of(List.of(1, 2), List.of(3, List.of(4)), 5)));

        assertEquals(List.of(1, 2, 3, 4, 5), ListHelperFlattenAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformReverse() {
        Parameters parameters = MockParametersFactory.create(Map.of(LIST, List.of(1, 2, 3)));

        assertEquals(List.of(3, 2, 1), ListHelperReverseAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformSortAscending() {
        Parameters parameters = MockParametersFactory.create(Map.of(LIST, List.of(3, 1, 2)));

        assertEquals(List.of(1, 2, 3), ListHelperSortAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformSortDescending() {
        Parameters parameters = MockParametersFactory.create(Map.of(LIST, List.of("b", "c", "a"), ORDER, "desc"));

        assertEquals(List.of("c", "b", "a"), ListHelperSortAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformSum() {
        Parameters parameters = MockParametersFactory.create(Map.of(LIST, List.of(1, 2, 3.5)));

        assertEquals(6.5, ListHelperSumAction.perform(parameters, parameters, mockedContext));
    }
}
