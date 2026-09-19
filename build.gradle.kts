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

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(shadowImpl)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/versions/**")
    exclude("META-INF/*.kotlin_module")
    mergeServiceFiles()
}

tasks.jar {
    archiveClassifier.set("nodeps")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
