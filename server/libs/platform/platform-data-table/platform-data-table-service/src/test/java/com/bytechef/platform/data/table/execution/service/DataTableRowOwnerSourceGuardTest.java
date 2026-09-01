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

package com.bytechef.platform.data.table.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.owner.Owner;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one structural guard this design rests on: an owner reaches a row statement through {@link DataTableRef} and
 * through nothing else.
 *
 * <p>
 * The previous row-level implementation passed a {@code RowOwnerFilter} beside the ref, which produced eight filtered
 * operations, eight unrestricted ones and four write gates -- sixteen entry points, each of which was a place the
 * scoping could be omitted, and one of them was. Counting them was the only way to know. With the owner behind the ref
 * the count is one, and this test is what keeps it there: any new parameter that could carry an owner reintroduces the
 * whole class of mistake, so it fails here rather than in review.
 *
 * @author Ivica Cardic
 */
class DataTableRowOwnerSourceGuardTest {

    /**
     * Types that can name an owner. A row operation taking any of them beside its ref would be a second source of the
     * predicate, and the two could disagree.
     */
    private static final List<Class<?>> OWNER_BEARING_TYPES = List.of(Owner.class, OwnerType.class);

    @Test
    void testNoRowOperationTakesAnOwnerBesideItsRef() {
        for (Method method : rowOperations()) {
            for (Parameter parameter : method.getParameters()) {
                Class<?> parameterType = parameter.getType();

                assertThat(OWNER_BEARING_TYPES)
                    .as(
                        "row operation '" + method.getName() + "' must take its owner from the DataTableRef, not as a "
                            + "parameter of its own")
                    .doesNotContain(parameterType);
            }
        }
    }

    /**
     * Every operation takes a ref, and it is first. If one did not, it would be naming its table some other way, and
     * whatever named it would also have had to choose an owner.
     */
    @Test
    void testEveryRowOperationTakesARefFirst() {
        List<Method> methods = rowOperations();

        assertThat(methods)
            .as("the reflection here must actually be seeing the operations")
            .hasSizeGreaterThanOrEqualTo(8);

        for (Method method : methods) {
            Class<?>[] parameterTypes = method.getParameterTypes();

            assertThat(parameterTypes)
                .as("row operation '" + method.getName() + "' must name its table with a DataTableRef")
                .isNotEmpty();

            assertThat(parameterTypes[0])
                .as("row operation '" + method.getName() + "' must take its DataTableRef first")
                .isEqualTo(DataTableRef.class);
        }
    }

    /**
     * The implementation is checked as well as the interface: a public overload added only on the impl would be
     * reachable from anything holding the concrete type, and the interface test alone would not see it.
     */
    @Test
    void testTheImplementationAddsNoOwnerBearingPublicOperation() {
        for (Method method : DataTableRowServiceImpl.class.getDeclaredMethods()) {
            if (isSynthetic(method) || !Modifier.isPublic(method.getModifiers())) {
                continue;
            }

            for (Parameter parameter : method.getParameters()) {
                Class<?> parameterType = parameter.getType();

                assertThat(OWNER_BEARING_TYPES)
                    .as("public method '" + method.getName() + "' must not take an owner beside its ref")
                    .doesNotContain(parameterType);
            }
        }
    }

    /**
     * {@code RowOwnerFilter} was the shape this design exists to avoid. Named rather than described, because a
     * re-introduction would arrive under the same name.
     */
    @Test
    void testRowOwnerFilterIsNotBack() {
        try {
            Class.forName("com.bytechef.platform.data.table.domain.RowOwnerFilter");

            fail("RowOwnerFilter is back: the owner must travel inside DataTableRef, not beside it");
        } catch (ClassNotFoundException expected) {
            assertThat(expected.getMessage()).contains("RowOwnerFilter");
        }
    }

    /**
     * The ref is what the predicate is built from, so the predicate builders must read it and take nothing else that
     * could name an owner.
     *
     * <p>
     * EVERY declared method of each name is checked, not the first one found. An overload taking an owner beside the
     * ref is precisely the reintroduction this guards against, and it would leave the single-argument form standing
     * beside it for a "findFirst" to land on.
     */
    @Test
    void testThePredicateBuildersReadTheRefAndNothingElse() {
        for (String predicateBuilderName : List.of("readableOwnerPredicate", "writableOwnerPredicate")) {
            List<Method> methods = Arrays.stream(RowQuerySqlBuilder.class.getDeclaredMethods())
                .filter(candidate -> {
                    String name = candidate.getName();

                    return name.equals(predicateBuilderName);
                })
                .toList();

            assertThat(methods)
                .as("RowQuerySqlBuilder must declare exactly one " + predicateBuilderName)
                .hasSize(1);

            Method method = methods.getFirst();

            assertThat(method.getParameterTypes())
                .as(predicateBuilderName + " must be built from the ref alone")
                .containsExactly(DataTableRef.class);
        }
    }

    /**
     * The read predicate and the write predicate are two functions rather than one with a flag, and they say different
     * things. A vendor-seeded reference row is every account's to read and nobody's to change, so collapsing them would
     * either open the write or close the read.
     *
     * <p>
     * The read's disjunction is bracketed, and that is not cosmetic. A caller's filters are appended after it, and
     * {@code AND} binds tighter than {@code OR}: unbracketed, a filtered read would return every unowned row in the
     * table regardless of what the filter said.
     */
    @Test
    void testTheReadPredicateAdmitsUnownedRowsAndTheWriteOneDoesNot() {
        DataTableRef dataTableRef = new DataTableRef(
            "guarded", 0, PlatformType.EMBEDDED, null, Owner.connectedUser(1L));

        assertThat(RowQuerySqlBuilder.readableOwnerPredicate(dataTableRef))
            .as("a read admits the rows belonging to nobody, as one bracketed term")
            .isEqualTo(" AND (\"owner_id\" = ? OR \"owner_id\" IS NULL)");

        assertThat(RowQuerySqlBuilder.writableOwnerPredicate(dataTableRef))
            .as("a write matches the run's own rows and nothing else")
            .isEqualTo(" AND \"owner_id\" = ?");
    }

    /**
     * A run with no owner is scoped to the unowned rows on both sides -- it does not fall through to an account's, and
     * an absent predicate would be exactly that fall-through.
     */
    @Test
    void testARunWithNoOwnerIsStillScoped() {
        DataTableRef dataTableRef = DataTableRef.shared("guarded", 0, PlatformType.EMBEDDED);

        assertThat(RowQuerySqlBuilder.readableOwnerPredicate(dataTableRef))
            .isEqualTo(" AND \"owner_id\" IS NULL");
        assertThat(RowQuerySqlBuilder.writableOwnerPredicate(dataTableRef))
            .isEqualTo(" AND \"owner_id\" IS NULL");
    }

    /**
     * The row operations, with the compiler's and the coverage agent's own additions filtered out.
     *
     * <p>
     * JaCoCo adds a static {@code $jacocoInit} to any interface carrying a default method, and it takes no ref. Left
     * in, it fails the ref-first check with a message about a method nobody wrote -- and would do so on a legitimate
     * default method, which is a spurious failure rather than the one this test exists for.
     */
    private static List<Method> rowOperations() {
        return Arrays.stream(DataTableRowService.class.getDeclaredMethods())
            .filter(method -> !isSynthetic(method))
            .filter(method -> !Modifier.isStatic(method.getModifiers()))
            .toList();
    }

    private static boolean isSynthetic(Method method) {
        String name = method.getName();

        return method.isSynthetic() || method.isBridge() || name.startsWith("$");
    }
}
