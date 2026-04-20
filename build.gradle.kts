plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.p2deps)
    application
}

group = "com.github.djaler"
version = providers.environmentVariable("VERSION").getOrElse("0.1.0-SNAPSHOT")

repositories {
    mavenCentral()
}

p2deps {
    into("eclipseMat") {
        p2repo("https://download.eclipse.org/mat/1.16.1/update-site/")
        install("org.eclipse.mat.api")
        install("org.eclipse.mat.hprof")
        install("org.eclipse.mat.parser")
        install("org.eclipse.mat.report")
    }
}

configurations {
    named("implementation") {
        extendsFrom(configurations["eclipseMat"])
    }
}

dependencies {
    implementation(libs.mcp.sdk)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // ICU4J — required by Eclipse MAT's MessageUtil (com.ibm.icu.text.MessageFormat)
    implementation("com.ibm.icu:icu4j:61.1")

    // Eclipse platform dependencies (from Maven Central)
    implementation("org.eclipse.platform:org.eclipse.core.runtime:3.31.100")
    implementation("org.eclipse.platform:org.eclipse.equinox.common:3.19.100")
    implementation("org.eclipse.platform:org.eclipse.equinox.preferences:3.11.100")
    implementation("org.eclipse.platform:org.eclipse.equinox.registry:3.12.100")
    implementation("org.eclipse.platform:org.eclipse.osgi:3.20.0")

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.github.djaler.jvmheapdumpmcp.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("jvm-heap-dump-mcp")
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform {
        if (!project.hasProperty("includeIntegration")) {
            excludeTags("integration")
        }
    }
    maxHeapSize = "2g"
}

tasks.register<JavaExec>("generateTestHeapDump") {
    group = "test data"
    description = "Generates test heap dumps for integration tests"
    classpath = project(":heap-dump-generator").sourceSets["main"].runtimeClasspath
    mainClass.set("com.github.djaler.jvmheapdumpmcp.generator.HeapDumpGeneratorKt")
    args = listOf(
        file("src/test/resources/test-heap-dump.hprof").absolutePath,
        file("src/test/resources/test-heap-dump-leaked.hprof").absolutePath,
    )
    maxHeapSize = "256m"
}
