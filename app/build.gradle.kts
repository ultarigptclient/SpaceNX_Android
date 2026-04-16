import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    id("kotlin-kapt")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val appVersionName = "8.4.9"
val appVersionParts = appVersionName.split(".").map { it.toInt() }
val appVersionCode = appVersionParts[0] * 10000 + appVersionParts[1] * 100 + appVersionParts[2]

android {
    namespace = "net.spacenx.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.spacenx.messenger"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "false"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../../../signing/scalling_release.jks")
            storePassword = "49100hansy!"
            keyAlias = "sCalling"
            keyPassword = "49100hansy!"
        }
    }

    buildTypes {
        debug {
            // 프로가드 비활성화
            isMinifyEnabled = false

            // 기본 및 프로젝트 전용 프로가드 설정 (함수 호출 방식)
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )

            // 또는 속성 대입 방식 (기존에 적어드린 방식과 동일하며 둘 다 작동합니다)
            // setProguardFiles(listOf(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"))

            // ManifestPlaceholders 설정 (Map 형태)
            manifestPlaceholders["enableCrashReporting"] = "false"
        }

        release {
            // only arm cpu
            ndk {
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
                // abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
            }

            // 불필요 리소스 삭제
            isShrinkResources = true

            // 프로가드 활성화
            isMinifyEnabled = true

            // 기본 프로가드 설정 및 프로젝트에 필요한 설정
            setProguardFiles(listOf(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            ))

            // Use the signing configuration we just defined
            signingConfig = signingConfigs.getByName("release")

            manifestPlaceholders["enableCrashReporting"] = "true"

            // 2025-01-23
            // Execution failed for task ':app:uploadCrashlyticsMappingFileRelease'.
            // > Host name may not be empty
            firebaseCrashlytics {
                mappingFileUploadEnabled = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // BuildConfig.DEBUG 등 코드에서 접근 (ApiClient 의 TLS 가드 등)
    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // MockK 가 끌어오는 JUnit 5 transitive 의존성에 중복된 LICENSE 파일이 있어
            // androidTest 빌드 실패 방지
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Crashlytics mapping 업로드 비활성화 (호스트명 미설정 환경)
tasks.matching { it.name.contains("uploadCrashlyticsMappingFile") }.configureEach {
    enabled = false
}

dependencies {

    //implementation(project(":resource"))
    //implementation(project(":base"))
    //implementation(project(":service"))
    implementation(platform("com.google.firebase:firebase-bom:33.1.1"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process) //2026-03-11
    implementation(libs.androidx.activity.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.scalars)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation("androidx.security:security-crypto:1.1.0-alpha06") //2026-03-13 EncryptedSharedPreferences
    implementation("io.livekit:livekit-android:2.5.0") // LiveKit 통화
    implementation(libs.kwik) // QUIC raw bidi stream (Gateway2:18029, ALPN "neo")
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.mockk.android)
}
