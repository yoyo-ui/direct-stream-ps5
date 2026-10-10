plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnit()
    maxHeapSize = "1g"
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
