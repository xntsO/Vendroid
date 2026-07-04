plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}

if (hasProperty("develocity")) {
    println("Vendroid: Accepting scans.gradle.com terms of service")
    extensions.findByName("develocity")?.withGroovyBuilder {
        getProperty("buildScan")?.withGroovyBuilder {
            setProperty("termsOfUseUrl", "https://gradle.com/help/legal-terms-of-use")
            setProperty("termsOfUseAgree", "yes")
        }
    }
}
