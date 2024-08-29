dependencies {
    implementation("org.slf4j:slf4j-api")
    implementation("org.apache.commons:commons-lang3")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(project(":server:libs:config:app-config"))
    implementation(project(":server:libs:core:file-storage:file-storage-filesystem-service"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:platform:platform-code-workflow:platform-code-workflow-file-storage:platform-code-workflow-file-storage-api"))

    implementation(project(":server:ee:libs:core:file-storage:file-storage-aws"))
}