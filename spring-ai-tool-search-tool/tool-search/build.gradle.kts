plugins {
    id("com.bytechef.java-library-conventions")
}

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

// Temporary vendored fork of org.springaicommunity:tool-search-tool. The upstream
// release (2.1.0) builds against spring-ai 2.0.0-M4; ByteChef tracks 2.0.0-M7,
// which widened ToolCallAdvisor's constructor and broke binary compatibility.
// See ../README.md for source provenance and the removal plan.
dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:${libs.findVersion("spring-ai").get()}"))
    api("org.springframework.ai:spring-ai-client-chat")
    api("org.springframework.ai:spring-ai-model")
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
