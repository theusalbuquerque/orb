import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val githubRepository: String =
    localProps.getProperty("GITHUB_REPOSITORY", "")
        .trim()
        .ifBlank { "theusalbuquerque/orb" }

fun gradleProperty(name: String): String =
    providers.gradleProperty(name).orNull
        ?: localProps.getProperty(name)
        ?: providers.environmentVariable(name).orNull.orEmpty()

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val orbSupabaseUrl = gradleProperty("ORB_SUPABASE_URL")
val orbSupabasePublishableKey = gradleProperty("ORB_SUPABASE_PUBLISHABLE_KEY")
val orbGoogleWebClientId = gradleProperty("ORB_GOOGLE_WEB_CLIENT_ID")

// ============================================================
// VERSIONAMENTO DO ORB
// ============================================================
//
// Um único package:
//   com.music.orb
//
// Dois canais:
//   dev  -> Beta
//   prod -> Stable
//
// Version-code para X.Y.Z:
//
//   base = X*1_000_000 + Y*10_000 + Z*100
//
//   Beta:
//     base + 1..89
//
//   Stable:
//     base + 90
//
// Exemplo:
//
//   1.3.5-beta   -> 1_030_501
//   1.3.5-beta.2 -> 1_030_502
//   1.3.5        -> 1_030_590
//
// Dessa forma:
//
//   Beta < Stable < próxima versão Beta
//
// permitindo:
//   Stable -> Beta
//   Beta -> Stable
//
// sem downgrade de versionCode.
// ============================================================

val appVersionName = "1.6.2"

// 1  = "-beta"
// 2  = "-beta.2"
// 3  = "-beta.3"
// ...
// 89 = "-beta.89"
val betaRevision = 1

fun versionBase(version: String): Int {
    val parts = version.split('.')

    require(parts.size == 3) {
        "appVersionName must be MAJOR.MINOR.PATCH"
    }

    val major = parts[0].toInt()
    val minor = parts[1].toInt()
    val patch = parts[2].toInt()

    require(minor in 0..99 && patch in 0..99) {
        "minor/patch must be 0..99"
    }

    return major * 1_000_000 +
            minor * 10_000 +
            patch * 100
}

val versionBaseCode = versionBase(appVersionName)

require(betaRevision in 1..89) {
    "betaRevision must be 1..89"
}

android {
    namespace = "com.music.orb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.music.orb"

        minSdk = 26
        targetSdk = 36

        versionCode = versionBaseCode
        versionName = appVersionName

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        // ------------------------------------------------------------
        // Optional credentials are supplied by local properties/environment, never committed.
        buildConfigField("String", "MUSIXMATCH_SIGNING_SECRET", buildConfigString(gradleProperty("ORB_MUSIXMATCH_SIGNING_SECRET")))

        // Donation details are supplied only by the owner at build time.
        buildConfigField("String", "DONATION_RECIPIENT", buildConfigString(gradleProperty("ORB_DONATION_RECIPIENT")))
        buildConfigField("String", "DONATION_PIX_KEY", buildConfigString(gradleProperty("ORB_DONATION_PIX_KEY")))
        buildConfigField("String", "DONATION_USD_ACCOUNT", buildConfigString(gradleProperty("ORB_DONATION_USD_ACCOUNT")))
        buildConfigField("String", "DONATION_USD_ACCOUNT_TYPE", buildConfigString(gradleProperty("ORB_DONATION_USD_ACCOUNT_TYPE")))
        buildConfigField("String", "DONATION_USD_ROUTING", buildConfigString(gradleProperty("ORB_DONATION_USD_ROUTING")))
        buildConfigField("String", "DONATION_EUR_IBAN", buildConfigString(gradleProperty("ORB_DONATION_EUR_IBAN")))
        buildConfigField("String", "DONATION_CNY_IBAN", buildConfigString(gradleProperty("ORB_DONATION_CNY_IBAN")))
        buildConfigField("String", "DONATION_GBP_ACCOUNT", buildConfigString(gradleProperty("ORB_DONATION_GBP_ACCOUNT")))
        buildConfigField("String", "DONATION_GBP_SORT_CODE", buildConfigString(gradleProperty("ORB_DONATION_GBP_SORT_CODE")))
        buildConfigField("String", "DONATION_GBP_IBAN", buildConfigString(gradleProperty("ORB_DONATION_GBP_IBAN")))

        // GitHub Releases
        // ------------------------------------------------------------

        buildConfigField(
            "String",
            "GITHUB_REPOSITORY",
            "\"${
                githubRepository
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
            }\"",
        )

        // ------------------------------------------------------------
        // Orb identity / social database
        // ------------------------------------------------------------
        // Only the publishable Supabase key belongs in the APK. Never put a
        // privileged/service-role key here. GOOGLE_WEB_CLIENT_ID is the OAuth
        // Web client ID used as the audience of the Google ID token.

        buildConfigField(
            "String",
            "SUPABASE_URL",
            buildConfigString(orbSupabaseUrl),
        )

        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            buildConfigString(orbSupabasePublishableKey),
        )

        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            buildConfigString(orbGoogleWebClientId),
        )

        // ------------------------------------------------------------
        // Compatibilidade do SourceRegistry
        // ------------------------------------------------------------
        //
        // O antigo backend Render foi removido.
        //
        // SourceRegistry ainda conhece este campo, então mantemos apenas
        // um BuildConfig vazio para compatibilidade.
        //
        // NÃO coloque MODULE_INDEX_URL no local.properties.
        // NÃO coloque nenhuma URL do Render aqui.
        // ------------------------------------------------------------

        buildConfigField(
            "String",
            "MODULE_INDEX_URL",
            "\"\"",
        )

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "x86_64",
            )
        }
    }

    // ============================================================
    // NATIVE / CMAKE
    // ============================================================

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ============================================================
    // FLAVORS
    // ============================================================

    flavorDimensions += "env"

    productFlavors {

        // --------------------------------------------------------
        // BETA
        // --------------------------------------------------------

        create("dev") {
            dimension = "env"

            // Não existe applicationId diferente.
            //
            // Beta e Stable são:
            //
            //   com.music.orb
            //
            // Portanto um pode atualizar o outro.

            versionCode =
                versionBaseCode + betaRevision

            versionNameSuffix =
                if (betaRevision == 1) {
                    "-beta"
                } else {
                    "-beta.$betaRevision"
                }

            // Release-channel identity only. Feature availability is shared
            // with prod; do not use IS_BETA to gate app behaviour.
            buildConfigField(
                "Boolean",
                "IS_BETA",
                "true",
            )
        }

        // --------------------------------------------------------
        // STABLE
        // --------------------------------------------------------

        create("prod") {
            dimension = "env"

            // Stable ocupa sempre o slot +90.
            //
            // Portanto:
            //
            // beta 1..89 < stable
            //
            // Isso permite que qualquer Beta da mesma versão
            // seja substituída pela Stable.

            versionCode =
                versionBaseCode + 90

            // Release-channel identity only. Stable intentionally receives the
            // same feature set as dev; false only keeps update/version semantics.
            buildConfigField(
                "Boolean",
                "IS_BETA",
                "false",
            )
        }
    }

    // ============================================================
    // ASSINATURA RELEASE
    // ============================================================

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile =
                    rootProject.file(
                        signing.getProperty("storeFile")
                    )

                storePassword =
                    signing.getProperty("storePassword")

                keyAlias =
                    signing.getProperty("keyAlias")

                keyPassword =
                    signing.getProperty("keyPassword")
            }
        }
    }

    // ============================================================
    // BUILD TYPES
    // ============================================================

    buildTypes {

        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro",
            )

            signingConfig =
                signingConfigs.findByName("release")
        }
    }

    // ============================================================
    // NOME AUTOMÁTICO DOS APKs RELEASE
    // ============================================================
    //
    // devRelease:
    //
    //   Orb-1.3.5-beta.apk
    //
    // prodRelease:
    //
    //   Orb-1.3.5.apk
    //
    // Beta revision 2:
    //
    //   Orb-1.3.5-beta.2.apk
    //
    // Debugs continuam com o nome padrão do Gradle.
    // ============================================================

    applicationVariants.all {
        val currentVariant = this

        outputs.all {
            if (currentVariant.buildType.name == "release") {

                val apkOutput =
                    this as com.android.build.gradle.internal.api.BaseVariantOutputImpl

                apkOutput.outputFileName =
                    "Orb-${currentVariant.versionName}.apk"
            }
        }
    }

    // ============================================================
    // JAVA
    // ============================================================

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17
    }

    // ============================================================
    // BUILD FEATURES
    // ============================================================

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// ================================================================
// KOTLIN
// ================================================================

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        )
    }
}

// ================================================================
// NEWPIPE EXTRACTOR
// ================================================================

val newPipeExtractorRaw: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

dependencies {
    newPipeExtractorRaw(
        "com.github.TeamNewPipe:NewPipeExtractor:v0.26.3"
    )
}

val newPipeExtractorStripped =
    tasks.register<org.gradle.api.tasks.bundling.Jar>(
        "stripNewPipeExtractorUtils"
    ) {
        archiveFileName.set(
            "NewPipeExtractor-v0.26.3-noutils.jar"
        )

        destinationDirectory.set(
            layout.buildDirectory.dir("stripped-libs")
        )

        from(
            provider {
                newPipeExtractorRaw.map {
                    zipTree(it)
                }
            }
        ) {
            exclude(
                "org/schabi/newpipe/extractor/utils/Utils.class"
            )

            exclude(
                "org/schabi/newpipe/extractor/utils/Utils\$*.class"
            )
        }
    }

// ================================================================
// DEPENDÊNCIAS
// ================================================================

dependencies {

    // ------------------------------------------------------------
    // Compose
    // ------------------------------------------------------------

    val composeBom =
        platform(
            "androidx.compose:compose-bom:2024.12.01"
        )

    implementation(composeBom)

    implementation(
        "androidx.compose.ui:ui"
    )

    implementation(
        "androidx.compose.ui:ui-graphics"
    )

    implementation(
        "androidx.compose.ui:ui-tooling-preview"
    )

    implementation(
        "androidx.compose.material3:material3"
    )

    implementation(
        "androidx.compose.material:material-icons-extended"
    )

    implementation(
        "androidx.activity:activity-compose:1.9.3"
    )

    implementation(
        "androidx.navigation:navigation-compose:2.8.5"
    )

    implementation(
        "androidx.lifecycle:lifecycle-runtime-ktx:2.8.7"
    )

    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7"
    )

    implementation(
        "androidx.core:core-ktx:1.15.0"
    )

    implementation(
        "androidx.work:work-runtime-ktx:2.10.1"
    )

    debugImplementation(
        "androidx.compose.ui:ui-tooling"
    )

    // ------------------------------------------------------------
    // Media3
    // ------------------------------------------------------------

    implementation(
        "androidx.media3:media3-exoplayer:1.5.1"
    )

    implementation(
        "androidx.media3:media3-exoplayer-dash:1.5.1"
    )

    implementation(
        "androidx.media3:media3-session:1.5.1"
    )

    implementation(
        "androidx.media3:media3-common:1.5.1"
    )

    implementation(
        "androidx.media3:media3-datasource-okhttp:1.5.1"
    )

    implementation(
        "androidx.media3:media3-exoplayer-hls:1.5.1"
    )

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0"
    )

    // ------------------------------------------------------------
    // Coil / Artwork / Palette
    // ------------------------------------------------------------

    implementation(
        "io.coil-kt.coil3:coil-compose:3.0.4"
    )

    implementation(
        "io.coil-kt.coil3:coil-network-okhttp:3.0.4"
    )

    implementation(
        "androidx.palette:palette-ktx:1.0.0"
    )

    // ------------------------------------------------------------
    // Haze
    // ------------------------------------------------------------

    implementation(
        "dev.chrisbanes.haze:haze:1.3.1"
    )

    implementation(
        "dev.chrisbanes.haze:haze-materials:1.3.1"
    )

    // ------------------------------------------------------------
    // Orb Google identity + Supabase Social
    // ------------------------------------------------------------

    implementation(
        "androidx.credentials:credentials:1.6.0"
    )

    implementation(
        "androidx.credentials:credentials-play-services-auth:1.6.0"
    )

    implementation(
        "com.google.android.libraries.identity.googleid:googleid:1.2.0"
    )

    // AuthorizationClient supplies the short-lived YouTube OAuth bearer.
    implementation(
        "com.google.android.gms:play-services-auth:21.6.0"
    )

    implementation(
        platform("io.github.jan-tennert.supabase:bom:3.5.0")
    )

    implementation(
        "io.github.jan-tennert.supabase:auth-kt"
    )

    implementation(
        "io.github.jan-tennert.supabase:postgrest-kt"
    )

    implementation(
        "io.github.jan-tennert.supabase:realtime-kt"
    )

    // ------------------------------------------------------------
    // Ktor
    // ------------------------------------------------------------

    implementation(
        "io.ktor:ktor-client-core:3.4.2"
    )

    implementation(
        "io.ktor:ktor-client-okhttp:3.4.2"
    )

    implementation(
        "io.ktor:ktor-client-content-negotiation:3.4.2"
    )

    implementation(
        "io.ktor:ktor-serialization-kotlinx-json:3.4.2"
    )

    implementation(
        "org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0"
    )

    implementation(
        "io.ktor:ktor-client-websockets:3.4.2"
    )

    // ------------------------------------------------------------
    // NewPipe
    // ------------------------------------------------------------

    implementation(
        files(newPipeExtractorStripped)
    )

    implementation(
        "com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996"
    )

    implementation(
        "org.jsoup:jsoup:1.22.2"
    )

    implementation(
        "com.google.code.findbugs:jsr305:3.0.2"
    )

    implementation(
        "com.google.protobuf:protobuf-javalite:4.35.0"
    )

    implementation(
        "org.mozilla:rhino:1.8.1"
    )

    implementation(
        "org.mozilla:rhino-engine:1.8.1"
    )

    // ------------------------------------------------------------
    // Security / QuickJS / ONNX
    // ------------------------------------------------------------

    implementation(
        "androidx.security:security-crypto:1.1.0-alpha06"
    )

    implementation(
        "io.github.dokar3:quickjs-kt-android:1.0.5"
    )

    implementation(
        "com.microsoft.onnxruntime:onnxruntime-android:1.28.0"
    )

    // ------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------

    testImplementation(
        "junit:junit:4.13.2"
    )

    androidTestImplementation(
        "androidx.test.ext:junit:1.3.0"
    )

    androidTestImplementation(
        "androidx.test.espresso:espresso-core:3.7.0"
    )
}