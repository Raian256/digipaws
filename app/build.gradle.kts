import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Short hash of the commit the build was produced from, surfaced in the
// in-app info screen. Falls back to "unknown" outside a git checkout.
fun gitCommitHash(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().use { it.readText() }.trim()
        .ifEmpty { "unknown" }
} catch (e: Exception) {
    "unknown"
}

android {
    namespace = "neth.iecal.curbox"
    compileSdk = 34
    flavorDimensions += "version"

    lint {
        disable.add("NullSafeMutableLiveData")
        abortOnError = false
    }

    defaultConfig {
        applicationId = "neth.iecal.curbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 50
        versionName = "5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommitHash()}\"")
    }

    productFlavors {
        create("fdroid") {
            dimension = "version"
            versionNameSuffix = "-fdroid"
            buildConfigField("Boolean", "FDROID_VARIANT", "true")
        }

        create("playstore") {
            dimension = "version"
            versionNameSuffix = "-playstore"
            buildConfigField("Boolean", "FDROID_VARIANT", "false")
        }
    }


    splits {
        abi {
            isEnable = false

        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // required because of hardcoded f-droid values
            applicationVariants.all {
                val variant = this
                if (variant.flavorName == "fdroid") {
                    variant.outputs
                        .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
                        .forEach { output ->
                            val outputFileName = "app-fdroid-universal-release-unsigned.apk"
                            println("OutputFileName: $outputFileName")
                            output.outputFileName = outputFileName
                        }
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}



dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)


    // QR Scanner & Generator
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // Shizuku dependecies
    implementation (libs.api)
    implementation (libs.provider)

    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.mpandroidchart)
    implementation(libs.timerangepicker)

}
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        tasks.register("installAndGrantAccessibility$variantName") {
            group = "install"
            description = "Installs the app, grants Accessibility permission, and launches it"
            dependsOn("install$variantName")
            
            doLast {
                val adbPath = sdkComponents.adb.get().asFile.absolutePath
                val appId = "neth.iecal.curbox"
                Thread.sleep(2000)
                // Grant Accessibility Permission
                exec {
                    val combinedServices = "$appId/$appId.services.AppBlockerService:$appId/$appId.services.UsageTrackingService"

                    commandLine(adbPath, "shell", "settings", "put", "secure", "enabled_accessibility_services", combinedServices)
                }
                
                // Launch MainActivity
                exec {
                    commandLine(adbPath, "shell", "am", "start", "-n", "$appId/$appId.ui.activity.FragmentActivity")
                }
            }
        }
    }
}
