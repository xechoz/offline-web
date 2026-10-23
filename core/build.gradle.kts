import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.github.xechoz"
                artifactId = "offline-web-core"
                version = "0.1.0"

                pom {
                    name.set("offline-web-core")
                    description.set("Transparent offline web resources for Android WebView.")
                    url.set("https://github.com/xechoz/offline-web")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("xechoz")
                            name.set("xechoz")
                        }
                    }
                    scm {
                        url.set("https://github.com/xechoz/offline-web")
                        connection.set("scm:git:https://github.com/xechoz/offline-web.git")
                    }
                }
            }
        }
    }
}
