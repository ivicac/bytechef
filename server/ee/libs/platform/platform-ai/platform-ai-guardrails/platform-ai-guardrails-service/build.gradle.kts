dependencies {
    implementation("io.micrometer:micrometer-core")
    implementation("org.apache.commons:commons-lang3")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:platform:platform-api"))
    implementation(project(":server:libs:platform:platform-configuration:platform-configuration-api"))
    // Resolves a job principal id (project deployment id) to its workspace for AiGuardrailsAdvisorProviderImpl.
    implementation(project(":server:ee:libs:platform:platform-ai:platform-ai-workspace"))
    // Provides the injection-classifier SPI + exception (AiGatewayInjectionClassifier, AiGatewayGuardrailException) and
    // the model/provider/chat-model-factory types the prompt-based classifiers call through.
    implementation(project(":server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api"))
    // Implements the CE SPI seam (AiGuardrailsAdvisorProvider) so non-EE components can obtain this advisor.
    api(project(":server:libs:platform:platform-ai:platform-ai-api"))
    // AiGuardrailsAdvisor implements Spring AI's CallAdvisor/StreamAdvisor and takes ChatClientRequest/ChatResponse
    // types on its public surface, so callers registering it need these transitively too.
    api("org.springframework.ai:spring-ai-client-chat")
    api("org.springframework.ai:spring-ai-model")

    api(project(":server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api"))
    // SensitiveDataRedactor/SensitiveDataDetectors/PiiToken/PiiTokenSession now live in the CE sensitive-data-service
    // module; api(...) here also re-exposes platform-ai-sensitive-data-api transitively (SensitiveSpan, SensitiveKind,
    // SensitiveDataDetector, SensitiveDataMetrics) since AiGuardrails/StreamingResponseRedactor put those on their own
    // public surface too.
    api(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service"))

    // ProjectDeploymentService/ProjectService are named only by the advisor tests, which build a real
    // JobPrincipalWorkspaceResolver from mocked providers.
    testImplementation(project(":server:libs:automation:automation-configuration:automation-configuration-api"))
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(project(":server:libs:config:jackson-config"))
    // Brings master.xml onto the integration-test classpath so ai_guardrail_violation is actually created from the
    // changelog rather than assumed to be creatable.
    testImplementation(project(":server:libs:config:liquibase-config"))
    testImplementation(project(":server:libs:test:test-int-support"))
    testImplementation(project(":server:libs:test:test-support"))
}
