plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
