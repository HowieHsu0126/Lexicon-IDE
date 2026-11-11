import java.security.MessageDigest

plugins {
    id("com.android.library")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "dev.lexicon.data"
    compileSdk = 36
    // Any parser, normalization or prepared-row implementation change invalidates derived indexes.
    // Bind source bytes at configuration time so cached definitions never outlive their parser.
    val parserDigest = MessageDigest.getInstance("SHA-256")
    listOf("MdxReader.kt", "KingsoftPhonetics.kt", "Lzo1x.kt", "PreparedMdxIndex.kt").forEach { name ->
        parserDigest.update(name.toByteArray(Charsets.UTF_8))
        parserDigest.update(0.toByte())
        parserDigest.update(file("src/main/kotlin/dev/lexicon/data/mdict/$name").readBytes())
    }
    val parserVersion = "mdx-prepared-v1-" + parserDigest.digest().joinToString("") { "%02x".format(it) }
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MDX_PARSER_VERSION", "\"$parserVersion\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    api(project(":domain"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.sqlite.bundled)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.core)
}

// Personal build input. Source archives exclude data/dic and generated assets.
val dictionaryAssets = layout.buildDirectory.dir("generated/dictionaryAssets")
val prepareDictionaryAssets by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("dic")) { include("*.mdx"); into("dictionaries") }
    into(dictionaryAssets)
    doLast {
        val root = dictionaryAssets.get().dir("dictionaries").asFile.apply { mkdirs() }
        val rows = root.listFiles().orEmpty().filter { it.extension.equals("mdx", true) }.sortedBy { it.name }.map { file ->
            require(file.name.none { it == '\t' || it == '\n' || it == '\r' }) { "Unsupported dictionary filename" }
            require(file.length() <= 256L * 1024 * 1024) { "Each dictionary must be at most 256 MiB" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            digest.digest().joinToString("") { "%02x".format(it) } + "\t" + file.name
        }
        root.resolve("catalog.tsv").writeText(rows.joinToString("\n"))
    }
}
androidComponents.onVariants { variant -> variant.sources.assets?.addStaticSourceDirectory(dictionaryAssets.get().asFile.absolutePath) }
tasks.named("preBuild").configure { dependsOn(prepareDictionaryAssets) }
