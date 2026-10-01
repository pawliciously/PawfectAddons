import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("net.fabricmc.fabric-loom") version "1.17.20"
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.0"
}

fun prop(name: String): String = project.property(name) as String

val javaVersion = prop("java_version").toInt()

version = prop("mod_version")
group = prop("maven_group")

base { archivesName.set(prop("archives_base_name")) }

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    withSourcesJar()
}

kotlin {
    jvmToolchain(javaVersion)
}

repositories {
    mavenCentral()
    maven("https://maven.notenoughupdates.org/releases")
    maven("https://repo.nea.moe/releases")
    maven("https://maven.terraformersmc.com/releases")
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
    exclusiveContent {
        forRepository { maven("https://repo.hypixel.net/repository/Hypixel/") }
        filter { includeGroup("net.hypixel") }
    }
    exclusiveContent {
        // legacy-item-dfu's Minecraft 26.1.2 build isn't published upstream, so it lives in libs/maven.
        forRepositories(maven("https://maven.azureaaron.net/releases"), maven(uri("libs/maven")))
        filter { includeGroup("net.azureaaron") }
    }
}

val shadowImpl: Configuration = configurations.create("shadowImpl") {
    configurations.implementation.get().extendsFrom(this)
}

dependencies {
    minecraft("com.mojang:minecraft:${prop("minecraft_version")}")

    implementation("net.fabricmc:fabric-loader:${prop("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${prop("flk_version")}")
    compileOnly("com.terraformersmc:modmenu:${prop("modmenu_version")}")
    // Location events for the Discord status. Optional: Skyblocker and most SkyBlock mods ship it.
    compileOnly("net.hypixel:mod-api:${prop("hypixel_mod_api_version")}")

    // Profile viewer: rebuilds Hypixel's 1.8 item NBT into modern stacks, and prices items
    // the same way SkyHelper does. Both are Apache-2.0 and nested jar-in-jar, so Fabric loads
    // a single copy even when Skyblocker ships them too.
    include(implementation("net.azureaaron:legacy-item-dfu:${prop("legacy_item_dfu_version")}")!!)
    include(implementation("net.azureaaron:networth-calculator:${prop("networth_calculator_version")}")!!)
}

loom {
    runs {
        named("client") {
            isIdeConfigGenerated = true
            jvmArguments.addAll("-Xmx4G")
            programArguments.addAll("--quickPlayMultiplayer", "hypixel.net")
        }
        removeIf { it.name == "server" }
    }
}

tasks.processResources {
    val props = mapOf(
        "version" to version,
        "minecraft_dependency" to prop("minecraft_dependency"),
        "loader_version" to prop("loader_version"),
        "flk_version" to prop("flk_version"),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaVersion)
}

// Nothing is shaded today (shadowImpl is empty), and Shadow's jar is built from the raw class
// output, which lacks Loom's nested jar-in-jar libraries and the fabric.mod.json that lists
// them. So Loom's own jar is the release jar. Turn this back on if something needs shading,
// and build it from Loom's jar when you do.
tasks.named<ShadowJar>("shadowJar") {
    enabled = false
    archiveClassifier.set("shadow")
    configurations = listOf(shadowImpl)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/versions/**")
    exclude("META-INF/*.kotlin_module")
    mergeServiceFiles()
}

tasks.jar {
    archiveClassifier.set("")
}
