import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

group = "io.reitmaier"
version = "1.0"

repositories {
  mavenCentral()
  maven(url = "https://jitpack.io")
}

java {
  // align with ktor/docker JRE
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}
tasks {
  withType<KotlinCompile>().configureEach {
    kotlinOptions {
      jvmTarget = "${JavaVersion.VERSION_11}"
//      freeCompilerArgs += listOf("-Xuse-experimental=kotlin.Experimental", "-progressive")
    }
  }
}
application {
  mainClass.set("io.reitmaier.speechbox.Main")
}

tasks {
  val fatJar = register<Jar>("fatJar") {
    dependsOn.addAll(listOf("compileJava", "compileKotlin", "processResources")) // We need this for Gradle optimization to work
    archiveClassifier.set("standalone") // Naming the jar
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes(mapOf("Main-Class" to application.mainClass)) } // Provided we set it up in the application plugin configuration
    val sourcesMain = sourceSets.main.get()
    val contents = configurations.runtimeClasspath.get()
      .map { if (it.isDirectory) it else zipTree(it) } +
      sourcesMain.output
    from(contents)
  }
  build {
    dependsOn(fatJar) // Trigger fat jar creation during build
  }
}

dependencies {

  // raspberry pi i/o
  implementation(libs.pi4j.core)
  implementation(libs.pi4j.device)

  // shell/process
  implementation(libs.github.pgreze.kotlin.process)

  // audio recording
  implementation(libs.github.axet.tarsosdsp)
  // utilities
  implementation(libs.kotlinx.datetime)

  // logging
  implementation(libs.logback.classic)
  implementation(libs.kotlin.inline.logger)

  // Coroutines
  implementation(libs.kotlinx.coroutines.core)

  // cancellable infinite coroutine loops & racing co-routines
  implementation(libs.louiscad.splitties.coroutines)

  // Result monad for modelling success/failure operations
  implementation(libs.kotlin.result)
  implementation(libs.kotlin.result.coroutines)

  // retry higher-order function
  implementation(libs.kotlin.retry)

  // KInputEvents
  implementation(libs.github.treitmaier.kinputevents)

  // ktor
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.client.auth)
}