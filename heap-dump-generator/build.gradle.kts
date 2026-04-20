plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.github.djaler.jvmheapdumpmcp.generator.HeapDumpGeneratorKt")
}
