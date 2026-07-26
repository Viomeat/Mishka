import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.baselineProfile)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

dependencies {
    baselineProfile(projects.baselineprofile)
    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.bundles.androidx.lifecycle)
    implementation(libs.bundles.androidx.navigation)
    implementation(libs.bundles.room)
    implementation(libs.bundles.kotlinx)
    implementation(libs.bundles.ktor)
    implementation(libs.bundles.miuix)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.material.icons.extended)
    implementation(libs.hiddenapibypass)
    implementation(libs.quickie.bundled)
    implementation(libs.scripta.editor)
    ksp(libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

val properties = Properties()
runCatching { properties.load(project.rootProject.file("local.properties").inputStream()) }
val keystorePath: String? = properties.getProperty("KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH")
val keystorePwd: String? = properties.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
val alias: String? = properties.getProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
val pwd: String? = properties.getProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")

@Suppress("UnstableApiUsage")
android {
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePwd
                keyAlias = alias
                keyPassword = pwd
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            optimization.enable = true
            vcsInfo.include = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileSdk {
        version = release(ProjectConfig.Android.COMPILE_SDK) {
            minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
        }
    }
    defaultConfig {
        applicationId = ProjectConfig.PACKAGE_NAME
        minSdk = ProjectConfig.Android.MIN_SDK
        targetSdk = ProjectConfig.Android.TARGET_SDK
        versionName = ProjectConfig.VERSION_NAME
        versionCode = getGitVersionCode()
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    namespace = ProjectConfig.PACKAGE_NAME
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }
    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            include("arm64-v8a")
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("app/compose_compiler_config.conf")
    )
}

baselineProfile {
    automaticGenerationDuringBuild = false
}

androidComponents {
    finalizeDsl { ext ->
        // 插件只关旧 DSL 的 isMinifyEnabled，管不到随 initWith(release) 继承来的 optimization.enable
        ext.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
        if (keystorePath == null) {
            val debugSigning = ext.signingConfigs.getByName("debug")
            listOf("nonMinifiedRelease", "benchmarkRelease").forEach { name ->
                ext.buildTypes.findByName(name)?.signingConfig = debugSigning
            }
        }
    }
}

abstract class DownloadGeoFilesTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val geoFilesUrls = mapOf(
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
//            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/country.mmdb" to "country.mmdb",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        )
        val dir = outputDir.get().asFile
        dir.mkdirs()
        geoFilesUrls.forEach { (downloadUrl, outputFileName) ->
            val outputPath = File(dir, outputFileName)
            URI(downloadUrl).toURL().openStream().use { input ->
                Files.copy(input, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("$outputFileName downloaded to $outputPath")
            }
        }
    }
}

val downloadGeoFiles = tasks.register<DownloadGeoFilesTask>("downloadGeoFiles") {
    description = "downloadGeoFiles"
    outputDir.set(layout.projectDirectory.dir("src/main/assets"))
}

val mihomoSubmoduleDir = rootProject.layout.projectDirectory.dir("mihomo")
val mishkaCoreSourceDir = rootProject.layout.projectDirectory.dir("app/src/main/native/mishka_core")
val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor")
val mihomoVersion = providers.gradleProperty("mihomo.version").orElse("dev").get()
val mihomoVersionPath = "github.com/metacubex/mihomo/constant.Version"
val ndkDirectoryProvider = androidComponents.sdkComponents.ndkDirectory

val supportedAbis = listOf("arm64-v8a")

// libmihomo.so 同时承担两个职责：
//  1. 订阅导入 JNI 路径（libmishka_jni.so dlopen + dlsym mishkaFetchAndValid 等）
//  2. mihomo runtime（薄 wrapper libmihomo_runner.so dlopen + dlsym mihomoEntry，fork+exec 启动）
val buildMihomoTasks = supportedAbis.map { abi ->
    val suffix = abi.replace("-", "_")
    tasks.register<GoBuildTask>("buildMihomo_$suffix") {
        group = "mihomo"
        description = "Build unified mihomo + JNI c-shared library (libmihomo.so) for $abi"
        goSourceDir.set(mishkaCoreSourceDir)
        this.abi.set(abi)
        versionName.set(mihomoVersion)
        buildTags.set(mihomoBuildTags)
        cgoEnabled.set(true)
        buildMode.set(GoBuildTask.BuildMode.CShared)
        ndkDirectory.set(ndkDirectoryProvider)
        minSdk.set(ProjectConfig.Android.MIN_SDK)
        moduleVersionPath.set(mihomoVersionPath)
        outputFile.set(layout.projectDirectory.file("src/main/jniLibs/$abi/libmihomo.so"))
    }
}

tasks.named("preBuild") {
    dependsOn(buildMihomoTasks)
}

// CMake 链接阶段引用 libmihomo.so + libmihomo.h
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(buildMihomoTasks)
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.add("**")
    }
}

base {
    archivesName.set(
        "${ProjectConfig.APP_NAME}-v${ProjectConfig.VERSION_NAME}(${getGitVersionCode()})",
    )
}
