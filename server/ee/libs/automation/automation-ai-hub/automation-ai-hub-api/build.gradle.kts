dependencies {
    api("io.projectreactor:reactor-core")
    api(project(":server:libs:platform:platform-configuration:platform-configuration-api"))
    api(project(":server:ee:libs:platform:platform-ai-hub:platform-ai-hub-api"))

    implementation("org.springframework:spring-core")
    implementation("org.springframework.data:spring-data-jdbc")
    implementation(project(":server:libs:core:commons:commons-data"))
    implementation(project(":server:libs:automation:automation-asset-file:automation-asset-file-api"))
    implementation(project(":server:libs:automation:automation-configuration:automation-configuration-api"))
    implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))
    api(project(":server:libs:platform:platform-ai:platform-ai-auto-memory:platform-ai-auto-memory-api"))
    implementation(project(":server:libs:platform:platform-api"))

    testImplementation(project(":server:libs:test:test-support"))
}
