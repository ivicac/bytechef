dependencies {
    api("org.springframework.data:spring-data-jdbc")
    api(project(":server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api"))
    api(project(":server:libs:platform:platform-connection:platform-connection-api"))
    api(project(":server:libs:platform:platform-configuration:platform-configuration-api"))

    implementation(project(":server:libs:core:commons:commons-util"))
}
