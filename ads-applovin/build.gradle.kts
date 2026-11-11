plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = findProperty("group")?.toString() ?: "com.github.e-hai.AdsKit-Android"
version = findProperty("version")?.toString() ?: libs.versions.sdkVersion.get()

android {
    namespace = "com.kit.ads.provider.applovin"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release")
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "AdsKit-applovin"

                pom {
                    name.set("AdsKit-applovin")
                    description.set("AdsKit AppLovin MAX mediation provider adapter")
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
}

dependencies {
    api(project(":ads-core"))

    // Applovin start
    implementation(libs.applovin.mediation.chartboost)
    implementation(libs.applovin.mediation.fyber)
    implementation(libs.applovin.mediation.inmobi)
    implementation(libs.applovin.mediation.vungle)
    implementation(libs.applovin.mediation.maio) {
        exclude(group = "com.maio", module = "android-sdk-v2")
    }
    implementation(libs.applovin.mediation.facebook)
    implementation(libs.applovin.mediation.mintegral)
    implementation(libs.applovin.mediation.unity.ads)
    implementation(libs.applovin.mediation.mytarget)
    implementation(libs.applovin.sdk)
    implementation(libs.applovin.mediation.amazon.tam)
    implementation(libs.amazon.aps.sdk)
    implementation(libs.play.services.ads.identifier)
    implementation(libs.play.services.base)
    implementation(libs.applovin.mediation.google.ad.manager)
    implementation(libs.applovin.mediation.google)
    implementation(libs.picasso)
    implementation(libs.androidx.recyclerview)
    implementation(libs.applovin.mediation.ironsource)
    implementation(libs.applovin.mediation.line)
    implementation(libs.applovin.mediation.mobilefuse)
    implementation(libs.applovin.mediation.moloco)
    implementation(libs.applovin.mediation.ogury)
    implementation(libs.applovin.mediation.bytedance)
    implementation(libs.applovin.mediation.smaato)
    implementation(libs.applovin.mediation.verve)
    implementation(libs.applovin.mediation.yandex)
    // Applovin end
}
