dependencies {
    implementation("org.apache.commons:commons-lang3")
    implementation("jakarta.validation:jakarta.validation-api")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.security:spring-security-core")
    implementation(project(":server:libs:core:commons:commons-util"))

    implementation(project(":server:ee:libs:embedded:embedded-configuration:embedded-configuration-api"))
    implementation(project(":server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-api"))
    // ConnectedUserAiGatewayRoutingPolicyFacadeImpl and ConnectedUserBeforeDeleteEventListener bind/unbind the AI
    // Gateway routing policy bound to a connected user (embedded phase 2). Legal direction: embedded depending on
    // platform, never the reverse.
    implementation(project(":server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api"))

    testImplementation("org.mockito:mockito-core")
}
