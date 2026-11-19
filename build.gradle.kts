import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import java.time.LocalDateTime
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Opcodes
import org.gradle.api.attributes.Usage

plugins { java }

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.ow2.asm:asm:9.9") }
}

repositories { google(); mavenCentral() }

val kotlinCompiler by configurations.creating
val sourceLibraries by configurations.creating {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
val sourceTestLibraries by configurations.creating {
    extendsFrom(sourceLibraries)
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}

dependencies {
    kotlinCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    sourceLibraries("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    sourceLibraries("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    sourceLibraries("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.12.0")
    sourceLibraries("com.squareup.okhttp3:okhttp:5.5.1")
    sourceLibraries("org.jsoup:jsoup:1.24.1")
    sourceLibraries("javax.inject:javax.inject:1")
    sourceTestLibraries("junit:junit:4.13.2")
}

val sdkClasses = layout.buildDirectory.dir("sdk-classes")
val sourceClasses = layout.buildDirectory.dir("source-classes")
val normalizedSourceClasses = layout.buildDirectory.dir("source-runtime-classes")
val sourceTestClasses = layout.buildDirectory.dir("source-test-classes")
val sourceToolClasses = layout.buildDirectory.dir("source-tool-classes")
val sdkFiles = fileTree("sdk/src/main/kotlin") { include("**/*.kt") }
val sourceFiles = fileTree("sources/src/main/kotlin") { include("**/*.kt") }
val sourceTestFiles = fileTree("sources/src/test/kotlin") { include("**/*.kt") }
val sourceToolFiles = fileTree("tools/src/main/kotlin") { include("**/*.kt") }
val metadata = JsonSlurper().parse(file("bundle.json")) as Map<*, *>
val bundleId = metadata["id"].toString()
val bundleVersion = (metadata["versionCode"] as Number).toInt()
val apiVersion = (metadata["apiVersion"] as Number).toInt()
require(bundleId.matches(Regex("[a-z0-9][a-z0-9-]+")) && bundleVersion > 0 && apiVersion > 0)

val compileSdk by tasks.registering(JavaExec::class) {
    inputs.files(sdkFiles, sourceLibraries)
    outputs.dir(sdkClasses)
    classpath = kotlinCompiler
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    doFirst {
        delete(sdkClasses.get().asFile)
        args = listOf("-no-stdlib", "-no-reflect", "-jvm-target", "17", "-language-version", "2.2", "-api-version", "2.2", "-module-name", "TenseiSourceApi",
            "-classpath", sourceLibraries.asPath, "-d", sdkClasses.get().asFile.absolutePath) + sdkFiles.files.sortedBy { it.path }.map { it.absolutePath }
    }
}
val sdkJar by tasks.registering(Jar::class) {
    dependsOn(compileSdk)
    archiveFileName.set("tensei-source-api-$apiVersion.jar")
    destinationDirectory.set(layout.buildDirectory.dir("sdk"))
    from(sdkClasses)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val compileSources by tasks.registering(JavaExec::class) {
    dependsOn(sdkJar)
    inputs.files(sourceFiles, sourceLibraries, sdkJar.flatMap { it.archiveFile })
    outputs.dir(sourceClasses)
    classpath = kotlinCompiler
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    doFirst {
        delete(sourceClasses.get().asFile)
        val sdkClasspath = files(sdkJar.get().archiveFile, sourceLibraries).asPath
        args = listOf("-no-stdlib", "-no-reflect", "-jvm-target", "17", "-language-version", "2.2", "-api-version", "2.2", "-module-name", "TenseiOfficialSources",
            "-classpath", sdkClasspath, "-d", sourceClasses.get().asFile.absolutePath) + sourceFiles.files.sortedBy { it.path }.map { it.absolutePath }
    }
}
val normalizeSourceBytecode by tasks.registering {
    dependsOn(compileSources)
    inputs.dir(sourceClasses)
    outputs.dir(normalizedSourceClasses)
    doLast {
        val input = sourceClasses.get().asFile
        val output = normalizedSourceClasses.get().asFile
        delete(output)
        input.walkTopDown().filter { it.isFile }.forEach { original ->
            val target = output.resolve(original.relativeTo(input))
            target.parentFile.mkdirs()
            if (original.extension == "class") {
                val writer = ClassWriter(0)
                ClassReader(original.readBytes()).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                    override fun visitSource(source: String?, debug: String?) { super.visitSource(source, null) }
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                        if (descriptor == "Lkotlin/jvm/internal/SourceDebugExtension;") null else super.visitAnnotation(descriptor, visible)
                }, 0)
                target.writeBytes(writer.toByteArray())
            } else original.copyTo(target)
        }
    }
}
val sourceJar by tasks.registering(Jar::class) {
    dependsOn(normalizeSourceBytecode)
    archiveFileName.set("$bundleId-$bundleVersion.jar")
    destinationDirectory.set(layout.buildDirectory.dir("sources"))
    from(normalizedSourceClasses)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val compileSourceTests by tasks.registering(JavaExec::class) {
    dependsOn(sourceJar)
    inputs.files(sourceTestFiles, sourceTestLibraries, sourceJar.flatMap { it.archiveFile }, sdkJar.flatMap { it.archiveFile })
    outputs.dir(sourceTestClasses)
    classpath = kotlinCompiler
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    doFirst {
        delete(sourceTestClasses.get().asFile)
        val testClasspath = files(sourceJar.get().archiveFile, sdkJar.get().archiveFile, sourceTestLibraries).asPath
        args = listOf("-no-stdlib", "-no-reflect", "-jvm-target", "17", "-language-version", "2.2", "-api-version", "2.2", "-module-name", "TenseiSourceTests",
            "-classpath", testClasspath, "-d", sourceTestClasses.get().asFile.absolutePath) + sourceTestFiles.files.sortedBy { it.path }.map { it.absolutePath }
    }
}
val compileSourceTools by tasks.registering(JavaExec::class) {
    dependsOn(sourceJar)
    inputs.files(sourceToolFiles, sourceLibraries, sourceJar.flatMap { it.archiveFile }, sdkJar.flatMap { it.archiveFile })
    outputs.dir(sourceToolClasses)
    classpath = kotlinCompiler
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    doFirst {
        delete(sourceToolClasses.get().asFile)
        args = listOf("-no-stdlib", "-no-reflect", "-jvm-target", "17", "-language-version", "2.2", "-api-version", "2.2",
            "-classpath", files(sourceJar.get().archiveFile, sdkJar.get().archiveFile, sourceLibraries).asPath,
            "-d", sourceToolClasses.get().asFile.absolutePath) + sourceToolFiles.files.sortedBy { it.path }.map { it.absolutePath }
    }
}
val sourceMetadata = layout.buildDirectory.file("sources/catalogue.json")
val generateSourceMetadata by tasks.registering(JavaExec::class) {
    dependsOn(compileSourceTools)
    inputs.files(sourceJar.flatMap { it.archiveFile }, "bundle.json")
    outputs.file(sourceMetadata)
    classpath = files(sourceToolClasses, sourceJar.flatMap { it.archiveFile }, sdkJar.flatMap { it.archiveFile }, sourceLibraries)
    mainClass.set("com.tensei.tools.SourceCatalogueMetadataKt")
    args(bundleId, sourceMetadata.get().asFile.absolutePath)
}
tasks.register<JavaExec>("smokeSources") {
    dependsOn(compileSourceTools)
    classpath = files(sourceToolClasses, sourceJar.flatMap { it.archiveFile }, sdkJar.flatMap { it.archiveFile }, sourceLibraries)
    mainClass.set("com.tensei.tools.SourceSmokeKt")
}
tasks.named<Test>("test") {
    dependsOn(compileSourceTests)
    testClassesDirs = files(sourceTestClasses)
    classpath = files(sourceTestClasses, "sources/src/test/resources", sourceJar.flatMap { it.archiveFile }, sdkJar.flatMap { it.archiveFile }, sourceTestLibraries)
    useJUnit()
}

fun androidSdkDirectory(): File {
    val local = file("local.properties")
    val properties = Properties()
    if (local.exists()) local.inputStream().use { properties.load(it) }
    return file(properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Set ANDROID_HOME or sdk.dir in local.properties; Android Build Tools 36.0.0 is required."))
}
val dexZip = layout.projectDirectory.file("releases/$bundleId-$bundleVersion.dex.zip")
val dexSources by tasks.registering(JavaExec::class) {
    dependsOn(sourceJar, tasks.named("test"))
    inputs.files(sourceJar.flatMap { it.archiveFile }, sdkJar.flatMap { it.archiveFile }, sourceLibraries, "bundle.json")
    outputs.file(dexZip)
    mainClass.set("com.android.tools.r8.D8")
    doFirst {
        val sdk = androidSdkDirectory()
        val d8 = sdk.resolve("build-tools/36.0.0/lib/d8.jar")
        val androidJar = sdk.resolve("platforms/android-34/android.jar").takeIf { it.exists() }
            ?: sdk.resolve("platforms/android-36/android.jar")
        check(d8.exists() && androidJar.exists()) { "Install build-tools;36.0.0 and platforms;android-34 (or 36)." }
        dexZip.asFile.parentFile.mkdirs()
        classpath = files(d8)
        args = listOf("--release", "--min-api", "26", "--lib", androidJar.absolutePath) +
            files(sdkJar.get().archiveFile, sourceLibraries).files.flatMap { listOf("--classpath", it.absolutePath) } +
            listOf("--output", dexZip.asFile.absolutePath, sourceJar.get().archiveFile.get().asFile.absolutePath)
    }
    doLast {
        // Stored bytes and a fixed local DOS timestamp avoid JDK/zlib/time-zone ZIP drift.
        val dexBytes = ZipFile(dexZip.asFile).use { zip -> zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() } }
        val crc = CRC32().apply { update(dexBytes) }.value
        ZipOutputStream(dexZip.asFile.outputStream()).use { zip ->
            val entry = ZipEntry("classes.dex").apply {
                method = ZipEntry.STORED
                size = dexBytes.size.toLong()
                compressedSize = size
                this.crc = crc
                setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
            }
            zip.putNextEntry(entry)
            zip.write(dexBytes)
            zip.closeEntry()
        }
    }
}
val verifyPayload by tasks.registering {
    dependsOn(dexSources)
    inputs.files(dexZip, sourceJar.flatMap { it.archiveFile })
    doLast {
        ZipFile(sourceJar.get().archiveFile.get().asFile).use { jar ->
            val classes = jar.entries().asSequence().filter { it.name.endsWith(".class") }.toList()
            check(classes.isNotEmpty() && classes.all { it.name.startsWith("com/tensei/app/data/remote/sources/") || it.name.startsWith("com/tensei/sources/") }) {
                "The extension must contain only source classes; host SDK and application classes are forbidden."
            }
        }
        ZipFile(dexZip.asFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            check(entries.size == 1 && entries.single().name == "classes.dex") { "Expected one source classes.dex and no APK or supporting app files." }
        }
        // A published version is immutable. Updating parsers requires a new versioned URL.
        val previous = ProcessBuilder("git", "show", "HEAD:releases/${dexZip.asFile.name}")
            .directory(projectDir).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val previousBytes = previous.inputStream.use { it.readBytes() }
        if (previous.waitFor() == 0) {
            check(previousBytes.contentEquals(dexZip.asFile.readBytes())) {
                "This versioned extension already exists in Git and its bytes changed. Increase versionCode in bundle.json."
            }
        }
    }
}
val publishSources by tasks.registering {
    dependsOn(verifyPayload, generateSourceMetadata)
    inputs.files(dexZip, sourceMetadata)
    outputs.file("index.json")
    doLast {
        val digest = MessageDigest.getInstance("SHA-256").digest(dexZip.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        val index = linkedMapOf("schemaVersion" to 1, "apiVersion" to apiVersion, "sources" to JsonSlurper().parse(sourceMetadata.get().asFile), "extensions" to listOf(linkedMapOf(
            "id" to bundleId, "versionCode" to bundleVersion,
            "downloadUrl" to "https://raw.githubusercontent.com/ahmedTensei/TenseiSources/main/releases/${dexZip.asFile.name}",
            "sha256" to digest, "factoryClass" to metadata["factoryClass"].toString()
        )))
        file("index.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(index)) + "\n")
        println("Published source index: $bundleId version $bundleVersion SHA-256 $digest")
    }
}
