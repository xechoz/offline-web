import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "io.github.xechoz.offlineweb"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.okhttp)
    // Public API exposes StateFlow/SharedFlow and suspend functions.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )

    coordinates("io.github.xechoz", "offline-web-core", "0.1.1")
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("offline-web-core")
        description.set("Transparent offline web resources for Android WebView.")
        url.set("https://github.com/xechoz/offline-web")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("xechoz")
                name.set("xechoz")
                email.set("xechoz@users.noreply.github.com")
                url.set("https://github.com/xechoz")
            }
        }
        scm {
            url.set("https://github.com/xechoz/offline-web")
            connection.set("scm:git:https://github.com/xechoz/offline-web.git")
            developerConnection.set("scm:git:ssh://git@github.com/xechoz/offline-web.git")
        }
    }
}
