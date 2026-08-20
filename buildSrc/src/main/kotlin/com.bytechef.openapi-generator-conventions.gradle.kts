import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/*
 * Repo-wide wiring for openapi-generator's two Jackson-annotation switches.
 *
 * openapi-generator 7.24.0's "spring" generator started unconditionally emitting
 * @JsonInclude(Include.NON_NULL) (and @JsonInclude(Include.NON_ABSENT) for nullable properties) plus
 * @JsonSetter(nulls = SKIP) on every optional model field. That silently drops explicit "field": null from
 * every response, a wire-format break unrelated to any schema change, and makes an incoming explicit null
 * leave the field's default in place instead of setting it.
 *
 * 7.25.0 added the switches 7.24.0 was missing: `generateJsonIncludeAnnotations` and
 * `generateJsonSetterNullsAnnotations`. Both already default to what this repo wants - the generator calls
 * it "7.23.0-equivalent output": no field-level @JsonInclude, no @JsonSetter(nulls = ...), inclusion and
 * null-handling governed entirely by the global ObjectMapper. But while they are unset the generator logs a
 * warning on every spring generation asking to be set explicitly, so they are set to "false" here: the
 * intent is stated rather than inherited from a default, and the two warnings go away.
 *
 * Until 7.25.0 there was no flag at all, so this plugin instead pointed every spring GenerateTask at a
 * vendored copy of the generator's own JavaSpring/pojo.mustache with the two annotation blocks deleted,
 * guarded by a `verifyOpenApiPojoTemplate` task that re-derived the copy on every generator bump. Two
 * config options replace all of it: no fork, no guard, no re-derivation.
 *
 * The options go on `additionalProperties`, not `configOptions`. openapi-generator merges both into the
 * codegen's additionalProperties map, which is where these two are read from, so either would reach the
 * generator - but every module in this repo configures its task with `configOptions.set(mapOf(...))`, and
 * `set` REPLACES a MapProperty wholesale. A plugin-level entry there would survive or be wiped depending on
 * whether this action ran before or after the module's own registration block. No module touches
 * `additionalProperties`, so there is nothing to collide with.
 *
 * This plugin exists because that protection used to be wired in exactly ONE of the repo's 23 spring-generator
 * modules, while every other one would silently generate the annotations on its next regeneration. Root's
 * `subprojects { apply(...) }` applies this plugin to every project, so a module is covered by registering a
 * spring GenerateTask - there is nothing to remember and nothing to opt into.
 *
 * A spec that asks for an annotation on a specific property still gets it: the `x-jackson-json-include-policy`
 * and `x-jackson-json-setter-nulls` vendor extensions are honored regardless of these flags. No spec in this
 * repo uses either.
 */

// GenerateTask cannot be referenced by type here. Putting openapi-generator-gradle-plugin on buildSrc's
// classpath would place it on every project's buildscript classpath, and Gradle then rejects each module's
// `plugins { alias(libs.plugins.org.openapi.generator) }` with "the plugin is already on the classpath with
// an unknown version, so compatibility cannot be checked" - all 37 of them. So the task is identified by
// class name instead, the same untyped shape com.bytechef.java-library-conventions already uses to
// post-process generated clients.
val openApiGenerateTaskClassName = "org.openapitools.generator.gradle.plugin.tasks.GenerateTask"

fun isOpenApiGenerateTask(task: Task): Boolean {
    // Gradle decorates task classes, so the runtime class is GenerateTask_Decorated - walk up to find it.
    var candidateClass: Class<*>? = task.javaClass

    while (candidateClass != null) {
        if (candidateClass.name == openApiGenerateTaskClassName) {
            return true
        }

        candidateClass = candidateClass.superclass
    }

    return false
}

@Suppress("UNCHECKED_CAST")
fun generatorNameOf(task: Task): Property<String> =
    task.javaClass.getMethod("getGeneratorName").invoke(task) as Property<String>

@Suppress("UNCHECKED_CAST")
fun additionalPropertiesOf(task: Task): MapProperty<String, Any> =
    task.javaClass.getMethod("getAdditionalProperties").invoke(task) as MapProperty<String, Any>

val springJacksonAnnotationOptions = mapOf<String, Any>(
    "generateJsonIncludeAnnotations" to "false",
    "generateJsonSetterNullsAnnotations" to "false")

plugins.withId("org.openapi.generator") {
    tasks.configureEach {
        if (!isOpenApiGenerateTask(this)) {
            return@configureEach
        }

        // Derived from generatorName rather than read at configuration time, so it holds whatever the
        // ordering between this action and the module's own registration block. Only the spring generator
        // reads these options; the repo's typescript-fetch and java client tasks get an empty map so their
        // inputs are unchanged.
        additionalPropertiesOf(this).putAll(
            generatorNameOf(this).map { generatorName ->
                if (generatorName == "spring") springJacksonAnnotationOptions else emptyMap()
            })
    }
}
