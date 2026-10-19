// Independent scene module; its parser namespace does not replace the existing desktop core.
plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.lz4.java)
    testImplementation(libs.junit)
}
tasks.withType<Test>().configureEach {
    useJUnit()
    systemProperty("java.awt.headless", "true")
}
