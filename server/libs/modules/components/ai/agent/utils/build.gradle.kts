dependencies {
    implementation(libs.io.github.a2asdk.a2a.java.sdk.client)
    implementation(libs.org.springaicommunity.spring.ai.agent.utils)
    implementation(project(":server:libs:core:file-storage:file-storage-api"))
    implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))
}
