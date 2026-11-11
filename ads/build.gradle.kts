plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = findProperty("group")?.toString() ?: "com.github.e-hai.AdsKit-Android"
version = findProperty("version")?.toString() ?: libs.versions.sdkVersion.get()

android {
    namespace = "com.kit.ads.bundle"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release")
    }
}

val adsBundleAarDir = layout.buildDirectory.dir("outputs/aar")
val adsBundleSdkVersion = libs.versions.sdkVersion.get()

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "adskit"

                pom {
                    name.set("AdsKit")
                    description.set("AdsKit full bundle Android mediation SDK")
                    url.set("https://github.com/e-hai/AdsKit-Android")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("e-hai")
                            name.set("e-hai")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/e-hai/AdsKit-Android.git")
                        developerConnection.set("scm:git:ssh://github.com/e-hai/AdsKit-Android.git")
                        url.set("https://github.com/e-hai/AdsKit-Android")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/e-hai/AdsKit-Android")
                credentials {
                    username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                    password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }

    // Capture DirectoryProperty / String only — Project must not be closed over in doLast (CC).
    tasks.named("bundleReleaseAar").configure {
        val aarDir = adsBundleAarDir
        val versionedName = "ads-v$adsBundleSdkVersion.aar"
        doLast {
            val dir = aarDir.get().asFile
            val source = dir.resolve("ads-release.aar")
            if (!source.exists()) return@doLast
            source.copyTo(dir.resolve(versionedName), overwrite = true)
        }
    }
}

dependencies {
    api(project(":ads-core"))
    api(project(":ads-admob"))
    api(project(":ads-applovin"))
}
