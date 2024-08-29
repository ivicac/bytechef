dependencies {
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(project(":server:libs:atlas:atlas-coordinator:atlas-coordinator-api"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:core:file-storage:file-storage-api"))
    implementation(project(":server:libs:core:tenant:tenant-api"))
    implementation(project(":server:libs:platform:platform-component:platform-component-registry:platform-component-registry-api"))
    implementation(project(":server:libs:platform:platform-configuration:platform-configuration-instance-api"))
    implementation(project(":server:libs:platform:platform-file-storage:platform-file-storage-api"))
    implementation(project(":server:libs:platform:platform-security:platform-security-web:platform-security-web-api"))
    implementation(project(":server:libs:platform:platform-user:platform-user-api"))
    implementation(project(":server:libs:platform:platform-workflow:platform-workflow-execution:platform-workflow-execution-api"))
    implementation(project(":server:libs:platform:platform-file-storage:platform-file-storage-api"))

    implementation(project(":server:ee:libs:automation:automation-api-platform:automation-api-platform-configuration:automation-api-platform-configuration-api"))
    implementation(project(":server:ee:libs:automation:automation-api-platform:automation-api-platform-handler:automation-api-platform-handler-api"))
}