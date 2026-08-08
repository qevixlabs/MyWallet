import java.util.Properties

/**
 * Release signing credentials live outside the build file and outside git.
 * The build still works without them — it just produces an unsigned release,
 * so a fresh clone is not blocked on having the keystore.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.mywallet"
    // Compile against 37 because current AndroidX requires it; still *target* 36,
    // which is what Play mandates for new apps from 31 Aug 2026. Compiling
    // against a newer SDK does not opt the app into its runtime behaviour changes.
    compileSdk = 37

    defaultConfig {
        // What the *installed app* is called. Android accepts an update only
        // when this matches the copy on the phone, so a build with a new id does
        // not update the old one — it installs beside it, with its own data
        // directory and an empty database. Every change costs one migration: a
        // JSON backup out of the old app and a restore into the new one.
        //
        // Changed twice, each time deliberately and each time before anything
        // made it permanent. com.niraj.mywallet became com.mywallet at 0.61.0;
        // com.mywallet became this at 1.1.2, because Google's ad servers refuse
        // to serve it — an interstitial request from com.mywallet is answered
        // with HTTP 403 where the identical build under any other id is served,
        // proved by running the same release-signed, minified APK under
        // com.mywallet, com.mywallet.trial and com.mywallet.debug within one
        // minute on one device. com.mywallet is a name generic enough that
        // somebody else has evidently already spent its reputation.
        //
        // **Frozen from here, and this time the lock is real.** It was affordable
        // twice only because the app was on neither Play nor AdMob; the whole
        // point of moving now is to register *this* id with AdMob, and an ad unit
        // is bound to the package it was created for and cannot be moved. Once
        // that registration or a Play listing exists, a change strands the
        // database — the only copy of the user's financial history — and orphans
        // every ad unit with it.
        //
        // `namespace` above is deliberately left at com.mywallet: that is the
        // Kotlin package the source actually lives in, it is invisible to
        // Android's package manager and to the ad servers, and renaming it would
        // touch every file in the app to change nothing anybody can observe.
        applicationId = "com.qevixlabs.mywallet"
        minSdk = 26
        targetSdk = 36
        versionCode = 236
        versionName = "1.2.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val storeFileName = keystoreProperties.getProperty("storeFile")
            if (storeFileName != null) {
                storeFile = rootProject.file(storeFileName)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // Where a build is going, which is a different question from whether it is
    // debuggable. One thing turns on it and it is not cosmetic: **an app on Play
    // may not update itself.** The policy is explicit — "An app distributed via
    // Google Play may not modify, replace, or update itself using any method
    // other than Google Play's update mechanism" — and this app has always
    // fetched its own APK from GitHub Releases and handed it to the system
    // installer, which is exactly that.
    //
    // So the updater is not a runtime `if`. The class that downloads and
    // installs lives in src/github, the Play build compiles a stub of the same
    // name in src/play, and REQUEST_INSTALL_PACKAGES and the FileProvider are
    // declared in src/github/AndroidManifest.xml — a permission in `main` is
    // merged into every flavour. What must not ship is *the code that installs
    // an APK*, and the only way to be sure of that is a source set the variant
    // never compiles.
    //
    // Nothing else differs. Same applicationId (Play would refuse a change and
    // an ad unit is bound to it), same signing, same everything the user sees
    // but one row in Settings.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            // Read by Settings, which is in `main` and cannot see either
            // updater: it withholds the whole *check for updates* offer rather
            // than showing one that would always answer "up to date".
            buildConfigField("boolean", "SELF_UPDATES", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATES", "false")
        }
    }

    buildTypes {
        // Two labels, because the two places they show up have different room.
        // `appLabel` is the application label — Settings, app info, notification
        // attribution — where a sentence-shaped name reads properly. `launcherLabel`
        // overrides it on MainActivity only, which is the caption under the icon and
        // the recents card, where a launcher gives you about ten characters a line
        // and breaks at spaces. Closing the gap in "MyMoney" keeps the name whole
        // there. Neither is the Play Store title: that one is typed into the console
        // and stays "My Money Tracker", so the phrase people search for is intact.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Distinct launcher name so a debug build sitting next to the real
            // one on a phone is obvious rather than a second identical icon. The
            // short form matters more here than the full name: "MyMoney Tracker
            // debug" would push the one word that tells them apart off the end.
            manifestPlaceholders["appLabel"] = "My Money Tracker debug"
            manifestPlaceholders["launcherLabel"] = "MyMoney debug"
        }
        release {
            manifestPlaceholders["appLabel"] = "My Money Tracker"
            manifestPlaceholders["launcherLabel"] = "MyMoney Tracker"
            // Falls back to unsigned when keystore.properties is absent.
            signingConfig = if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time on API 26 is fine, but desugaring keeps newer APIs available.
        isCoreLibraryDesugaringEnabled = false
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.nepali.date.picker)
    implementation(libs.androidx.biometric)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
