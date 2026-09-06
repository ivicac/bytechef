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

package com.bytechef.platform.ai.sensitivedata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Pins an invariant that {@link SensitiveDataDetectors}'s own javadoc claims but that nothing previously verified: the
 * Spring-bean set of {@link SensitiveDataDetector} implementations in this package and the
 * {@link SensitiveDataDetectors#builtIn()} list must name exactly the same classes. The two are independent definitions
 * -- production wires the former via classpath scanning, essentially every test constructs through the latter -- so a
 * detector added to one and not the other is either live-and-untested or tested-and-dead, and until this test existed
 * nothing caught it.
 *
 * <p>
 * Scoped to this package rather than a full classpath scan so that {@code OpenNlpSensitiveDataDetector} -- a real,
 * deliberately separate {@code @Component} detector that lives in the guardrails-opennlp module and is intentionally
 * absent from {@code builtIn()} because it is additive ML detection tested on its own via
 * {@code ApplicationContextRunner} -- is excluded by package boundary, not by an exception list someone has to remember
 * to maintain.
 * </p>
 *
 * <p>
 * Every detector in this package also carries {@code @ConditionalOnEEVersion}
 * ({@code @ConditionalOnProperty(prefix = "bytechef", name = "edition", havingValue = "ee")}), so the scanner is seeded
 * with a {@code bytechef.edition=ee} property. Without it, {@link ClassPathScanningCandidateComponentProvider} still
 * matches the {@code @Component} filter but then evaluates the {@code @Conditional} chain against a bare
 * {@code Environment} that has no such property, silently skips every candidate, and logs the misleading "Ignored
 * because not matching any filter" trace line even though the filter genuinely matched -- discovered by direct
 * reproduction while building this test: the scanner returned zero candidates until this property was added, matching
 * exactly what a real EE-edition deployment resolves the condition to.
 * </p>
 *
 * @author Ivica Cardic
 */
class SensitiveDataDetectorsTest {

    private static final String DETECTOR_PACKAGE = SensitiveDataDetectors.class.getPackageName();

    @Test
    void testBuiltInMatchesEveryComponentAnnotatedDetectorInThisPackage() {
        Set<Class<?>> componentAnnotatedDetectorClasses = scanForComponentAnnotatedDetectors();

        Set<Class<?>> builtInDetectorClasses = SensitiveDataDetectors.builtIn()
            .stream()
            .map(Object::getClass)
            .collect(Collectors.toSet());

        assertThat(builtInDetectorClasses).containsExactlyInAnyOrderElementsOf(componentAnnotatedDetectorClasses);
    }

    private static Set<Class<?>> scanForComponentAnnotatedDetectors() {
        StandardEnvironment eeEditionEnvironment = new StandardEnvironment();

        eeEditionEnvironment.getPropertySources()
            .addFirst(new MapPropertySource("eeEdition", Map.of("bytechef.edition", "ee")));

        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false, eeEditionEnvironment);

        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        return scanner.findCandidateComponents(DETECTOR_PACKAGE)
            .stream()
            .map(SensitiveDataDetectorsTest::loadClass)
            .filter(SensitiveDataDetector.class::isAssignableFrom)
            .collect(Collectors.toSet());
    }

    private static Class<?> loadClass(BeanDefinition beanDefinition) {
        try {
            return Class.forName(beanDefinition.getBeanClassName());
        } catch (ClassNotFoundException classNotFoundException) {
            throw new IllegalStateException(classNotFoundException);
        }
    }
}
