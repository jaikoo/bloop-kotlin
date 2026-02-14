plugins {
    kotlin("jvm") version "1.9.22"
    `maven-publish`
}

group = "com.bloop"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    // Tests use a custom assertion harness, not JUnit discovery
    enabled = false
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            artifactId = "bloop-client"
            pom {
                name.set("Bloop Client")
                description.set("Kotlin/JVM SDK for bloop error observability")
                url.set("https://github.com/jaikoo/bloop-kotlin")
                licenses {
                    license {
                        name.set("MIT")
                    }
                }
            }
        }
    }
}
