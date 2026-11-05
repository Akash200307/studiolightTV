plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { targetCompatibility = "17"; sourceCompatibility = "17" }
dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  testImplementation(kotlin("test-junit"))
}
