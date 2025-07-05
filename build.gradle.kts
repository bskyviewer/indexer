import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.spring") version "2.1.21"
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("sh.christian.ozone.generator") version "0.3.3"
}

group = "bskyviewer"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    lexicons("sh.christian.ozone:lexicons:0.3.3")

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("sh.christian.ozone:jetstream:0.3.3")
    implementation("org.meeuw.i18n:i18n-iso-639:3.8")

    implementation("org.apache.lucene:lucene-core:10.2.1")
    implementation("org.apache.lucene:lucene-queryparser:10.2.1")
    implementation("org.apache.lucene:lucene-analysis-common:10.2.1")
    implementation("org.apache.lucene:lucene-analysis-kuromoji:10.2.1")
    implementation("org.apache.lucene:lucene-analysis-morfologik:10.2.1")
    implementation("org.apache.lucene:lucene-analysis-nori:10.2.1")
    implementation("org.apache.lucene:lucene-analysis-smartcn:10.2.1")
    implementation("org.apache.lucene:lucene-analysis-stempel:10.2.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

lexicons.defaults {
    generateUnknownsForSealedTypes = true
    generateUnknownsForEnums = true
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<BootBuildImage>("bootBuildImage") {
    environment.putAll(
        mapOf(
            "BP_JVM_VERSION" to "21",
            "BP_JVM_CDS_ENABLED" to "true",
            "BPE_DELIM_JAVA_TOOL_OPTIONS" to " ",
            "BPE_APPEND_JAVA_TOOL_OPTIONS" to "--enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector -XX:MaxDirectMemorySize=40M  -XX:ReservedCodeCacheSize=80M -Xss256K",
        )
    )
}
