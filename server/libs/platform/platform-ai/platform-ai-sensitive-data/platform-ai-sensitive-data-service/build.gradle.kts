dependencies {
    compileOnly("com.github.spotbugs:spotbugs-annotations")

    implementation("org.jspecify:jspecify")
    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework:spring-context")
    // RegexPiiDetector/RegexSecretDetector stay @Component + @ConditionalOnEEVersion (see
    // platform-ai-guardrails-opennlp for the same pattern): the annotation lives in CE, so depending on it here is
    // legal, and keeping it preserves current behaviour -- EE apps set bytechef.edition=ee so the beans still
    // register, CE apps have no consumer yet.
    implementation(project(":server:libs:platform:platform-api"))
    // ToolSuspendConstants.SUSPENDED_SENTINEL is the single source of truth for the agent tool-suspend protocol's
    // sentinel value -- PiiTokenBoundaryToolCallingManager exempts it from tokenization/redaction entirely (it is a
    // control marker, not user content), and must compare against the same constant SuspendableToolCallingManager
    // reads, not a duplicated literal.
    implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))

    api(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api"))
    // PiiTokenSessionToolContext carries a PiiTokenSession across the tool-call boundary via Spring AI's
    // ToolContext, so callers of PiiTokenSessionToolContext#from need this type transitively too.
    api("org.springframework.ai:spring-ai-model")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":server:libs:test:test-support"))
}
