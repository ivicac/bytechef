dependencies {
    api(project(":server:libs:platform:platform-component:platform-component-api"))
    api(project(":server:libs:platform:platform-configuration:platform-configuration-api"))
    api(project(":server:libs:platform:platform-mcp:platform-mcp-api"))

    api("org.springframework:spring-expression")
    // AiGuardrailsAdvisorProvider returns Spring AI's Advisor type on its public surface.
    api("org.springframework.ai:spring-ai-client-chat")
    // AiGuardrailsAdvisorProvider#getMetrics returns SensitiveDataMetrics on its public surface.
    api(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api"))

    implementation("org.apache.commons:commons-lang3")
    implementation("org.springframework.ai:spring-ai-model")
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:core:evaluator:evaluator-api"))

    testImplementation(project(":server:libs:core:evaluator:evaluator-impl"))
    testImplementation(project(":server:libs:test:test-support"))
}
