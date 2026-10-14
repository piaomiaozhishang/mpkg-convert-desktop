// core 模块：纯 JVM Kotlin，零平台依赖（见《02 · 架构与目录结构》第 2 节）。
// 依赖白名单只有 org.lz4:lz4-java 一项；JUnit 仅测试期使用。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.lz4.java)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
