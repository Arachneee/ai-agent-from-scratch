plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":framework"))
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

application {
    mainClass.set("aiagent.shop.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
