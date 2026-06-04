dependencies {
    implementation(project(":server:ee:libs:ai:mcp:mcp-tool:mcp-tool-api"))
    api(project(":server:libs:platform:platform-configuration:platform-configuration-api"))

    implementation("org.springframework:spring-core")
    implementation("org.springframework.ai:spring-ai-commons")
    implementation("org.springframework.data:spring-data-jdbc")
    implementation(project(":server:libs:core:commons:commons-data"))
    implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))
    implementation(project(":server:libs:platform:platform-api"))

    testImplementation(project(":server:libs:platform:platform-ai:platform-ai-auto-memory:platform-ai-auto-memory-api"))
    testImplementation(project(":server:libs:test:test-support"))
}
