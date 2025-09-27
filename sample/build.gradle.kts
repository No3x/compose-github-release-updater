import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}


kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.appcompat)
            implementation(compose.material3)
            implementation(project.project(":library"))
        }
    }
}

android {
    namespace = "de.no3x.compose.githubreleaseupdater.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "de.no3x.compose.githubreleaseupdater.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = 34
        versionCode = 6
        versionName = "0.0.1"
        buildConfigField("String", "GITHUB_REPO_OWNER", "\"No3x\"")
        buildConfigField("String", "GITHUB_REPO_NAME", "\"compose-github-release-updater\"")
        val githubToken = providers.environmentVariable("GITHUB_RELEASES_TOKEN").orNull
            ?: providers.gradleProperty("github.releases.token").orNull
            ?: error("Nor GITHUB_RELEASES_TOKEN or github.releases.token are set")
        val escapedGithubToken = githubToken
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "GITHUB_RELEASES_TOKEN", "\"$escapedGithubToken\"")

    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}
