plugins {
    id("com.bytechef.java-library-conventions")
}

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

// Temporary vendored fork of org.springaicommunity:tool-searcher-vectorstore.
// See ../README.md for source provenance and the removal plan.
dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:${libs.findVersion("spring-ai").get()}"))
    api(project(":spring-ai-tool-search-tool:tool-search"))
    api("org.springframework.ai:spring-ai-vector-store")
    implementation("org.slf4j:slf4j-api")
}
