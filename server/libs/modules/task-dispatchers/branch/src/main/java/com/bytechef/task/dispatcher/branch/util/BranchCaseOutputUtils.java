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

package com.bytechef.task.dispatcher.branch.util;

import static com.bytechef.task.dispatcher.branch.constant.BranchTaskDispatcherConstants.CASES;
import static com.bytechef.task.dispatcher.branch.constant.BranchTaskDispatcherConstants.DEFAULT;
import static com.bytechef.task.dispatcher.branch.constant.BranchTaskDispatcherConstants.KEY;

import com.bytechef.commons.util.MapUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;

/**
 * @author Ivica Cardic
 */
public final class BranchCaseOutputUtils {

    private BranchCaseOutputUtils() {
    }

    public static String getCaseOutputKey(Map<String, ?> branchCase) {
        return branchCase.containsKey(KEY) ? String.valueOf(branchCase.get(KEY)) : DEFAULT;
    }

    public static Map<String, Object> toOutput(
        Map<String, ?> parameters, String selectedCaseOutputKey, @Nullable Object selectedCaseOutput) {

        Map<String, Object> output = new LinkedHashMap<>();

        List<Map<String, ?>> branchCases = MapUtils.getList(parameters, CASES, new TypeReference<>() {}, List.of());

        for (Map<String, ?> branchCase : branchCases) {
            output.put(getCaseOutputKey(branchCase), null);
        }

        output.putIfAbsent(DEFAULT, null);

        output.put(selectedCaseOutputKey, selectedCaseOutput);

        return output;
    }
}
