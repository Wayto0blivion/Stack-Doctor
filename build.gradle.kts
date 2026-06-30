import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import java.io.File

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Community is no longer published as a standalone artifact since 2025.3 (253);
        // the unified `intellijIdea(...)` dependency is the supported way to build against current
        // releases. We still depend only on platform + YAML APIs, so the plugin runs in every IDE.
        intellijIdea(providers.gradleProperty("platformVersion").get())

        // YAML PSI support (bundled in every IDE) — used to parse docker-compose files.
        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins").map { it.split(',').map(String::trim) }.get(),
        )

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)

    // Compile interface default methods as real JVM defaults, without Kotlin compatibility stubs.
    // Otherwise Kotlin generates delegating overrides for the default methods we inherit from
    // ToolWindowFactory (getIcon/getAnchor/isApplicable/isDoNotActivateOnStart/manage), which the
    // plugin verifier reports as deprecated/experimental API usages. Safe here: this is a leaf
    // plugin that exposes no interfaces for external code to implement.
    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

intellijPlatform {
    // Pure-Kotlin plugin with no Java/form sources, so form & @NotNull bytecode instrumentation
    // has nothing to do. Disabling it also avoids an Ant "taskdef" failure under the Gradle daemon.
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Open-ended upper bound: stable APIs only, so we don't pin to a single branch.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    // Author-side signing. Paths and the key passphrase are read from environment variables so no
    // secret ever lands in the repo. Reuse the SAME key + certificate for every future update.
    //   CERTIFICATE_CHAIN_FILE  → absolute path to chain.crt
    //   PRIVATE_KEY_FILE        → absolute path to private.pem
    //   PRIVATE_KEY_PASSWORD    → the key's passphrase
    // Run: ./gradlew signPlugin   (then verifyPluginSignature)
    signing {
        certificateChainFile = layout.file(providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map { File(it) })
        privateKeyFile = layout.file(providers.environmentVariable("PRIVATE_KEY_FILE").map { File(it) })
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Marketplace upload. PUBLISH_TOKEN comes from your Marketplace profile → My Tokens.
    // Run: ./gradlew publishPlugin
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    wrapper {
        gradleVersion = "9.1.0"
    }
}
