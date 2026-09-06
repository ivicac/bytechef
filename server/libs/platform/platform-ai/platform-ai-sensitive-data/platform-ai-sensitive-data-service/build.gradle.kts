dependencies {
    compileOnly("com.github.spotbugs:spotbugs-annotations")

    implementation("org.jspecify:jspecify")
    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework:spring-context")
    // PresidioRegexPiiDetector/RegexSecretDetector stay @Component + @ConditionalOnEEVersion (see
    // platform-ai-guardrails-opennlp for the same pattern): the annotation lives in CE, so depending on it here is
    // legal, and keeping it preserves current behaviour -- EE apps set bytechef.edition=ee so the beans still
    // register, CE apps have no consumer yet.
    implementation(project(":server:libs:platform:platform-api"))

    api(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":server:libs:test:test-support"))
}
