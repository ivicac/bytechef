dependencies {
    implementation(libs.org.springaicommunity.spring.ai.session.management)
    implementation("org.springframework:spring-jdbc")
    implementation(project(":server:libs:modules:components:ai:agent:chat-memory:chat-memory-jdbc-session"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))
}
