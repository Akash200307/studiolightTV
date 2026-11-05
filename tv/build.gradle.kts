plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose"); kotlin("plugin.serialization") }
android {
  namespace = "com.studiolight.tv"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.studiolight.tv"
    minSdk = 23
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }
  buildFeatures { compose = true; buildConfig = true }
  packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
  implementation(project(":core"))
  implementation("androidx.datastore:datastore-preferences:1.1.7")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.core:core-ktx:1.16.0")
  implementation(platform("androidx.compose:compose-bom:2025.08.00"))
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.animation:animation")
  implementation("androidx.compose.ui:ui")
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  testImplementation(kotlin("test-junit"))
  implementation("androidx.tv:tv-material:1.0.1")
  implementation("io.ktor:ktor-server-cio:3.3.3")
  implementation("io.ktor:ktor-server-websockets:3.3.3")
  testImplementation("io.ktor:ktor-client-cio:3.3.3")
  testImplementation("io.ktor:ktor-client-websockets:3.3.3")
}
