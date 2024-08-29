dependencies {
    implementation("org.apache.commons:commons-lang3")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation(project(":sdks:backend:java:component-api"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":cli:ee:commands:component:init:openapi"))
    implementation(project(":server:libs:platform:platform-data-sync:platform-data-sync-configuration:platform-data-sync-configuration-api"))

    testImplementation("org.springframework.data:spring-data-jdbc")
    testImplementation(project(":server:libs:config:liquibase-config"))
    testImplementation(project(":server:libs:test:test-int-support"))
}
