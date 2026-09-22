package com.apm.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * APM 慢方法检测 Gradle 插件。
 * 通过 AGP instrumentation API 在编译期注入方法计时代码。
 *
 * ## 插桩原理（对标微信 Matrix）
 * 1. 注册 [AsmClassVisitorFactory]，由 AGP 遍历当前 project 的 class 文件
 * 2. 使用 ASM 访问每个方法的入口和出口
 * 3. 在方法入口注入：ApmSlowMethodTracer.methodEnter(className#methodName)
 * 4. 在方法出口注入：ApmSlowMethodTracer.methodExit(className#methodName)
 * 5. 运行时 Tracer 自动计算方法耗时并上报
 */
class ApmSlowMethodPlugin : Plugin<Project> {

    /** 插件扩展配置。 */
    lateinit var extension: ApmSlowMethodExtension

    /** 防止 application/library 同时存在时重复注册插桩。 */
    private var instrumentationRegistered = false

    /**
     * 应用插件并延迟注册 AGP ASM instrumentation。
     *
     * 通过 [org.gradle.api.plugins.PluginManager.withPlugin] 等待 Android 插件就位：
     * 宿主 `plugins{}` 中先应用 `com.apm.slow-method` 再应用 Android 插件时，
     * apply() 阶段 [AndroidComponentsExtension] 尚不存在，立即查询会静默跳过插桩注册。
     *
     * @param project 当前 Gradle project。
     */
    override fun apply(project: Project) {
        // 注册插件扩展配置，供宿主 build.gradle 调整插桩开关和排除包名。
        extension = project.extensions.create(
            EXTENSION_NAME,
            ApmSlowMethodExtension::class.java
        )

        // 延迟到 Android 插件应用后再注册插桩，插件声明顺序不再影响结果。
        project.pluginManager.withPlugin(ANDROID_APPLICATION_PLUGIN_ID) {
            registerInstrumentation(project)
        }
        project.pluginManager.withPlugin(ANDROID_LIBRARY_PLUGIN_ID) {
            registerInstrumentation(project)
        }
        project.pluginManager.withPlugin(ANDROID_DYNAMIC_FEATURE_PLUGIN_ID) {
            registerInstrumentation(project)
        }

        // 非 Android module 应用插件时给出明确诊断，保持原有可观测性。
        project.afterEvaluate { evaluated ->
            if (!evaluated.plugins.hasPlugin(ANDROID_APPLICATION_PLUGIN_ID) &&
                !evaluated.plugins.hasPlugin(ANDROID_LIBRARY_PLUGIN_ID) &&
                !evaluated.plugins.hasPlugin(ANDROID_DYNAMIC_FEATURE_PLUGIN_ID)
            ) {
                evaluated.logger.warn(
                    "APM SlowMethod Plugin requires an Android application, library, or dynamic-feature plugin"
                )
            }
        }
    }

    /** AGP 可用后为所有 variant 注册 ASM 插桩；重复回调只生效一次。 */
    private fun registerInstrumentation(project: Project) {
        if (instrumentationRegistered) {
            return
        }
        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
        if (androidComponents == null) {
            // 理论上 withPlugin 触发时扩展必然存在；保留兜底诊断。
            project.logger.warn("APM SlowMethod Plugin requires Android Gradle Plugin")
            return
        }
        instrumentationRegistered = true

        androidComponents.onVariants { variant ->
            // 仅插桩当前 project，避免把 APM SDK 依赖类插桩后造成 Tracer 递归。
            variant.instrumentation.transformClassesWith(
                ApmSlowMethodAsmClassVisitorFactory::class.java,
                InstrumentationScope.PROJECT
            ) { params ->
                params.enabled.set(extension.enabled)
                params.excludePackages.set(extension.excludePackages)
            }
            // 插桩会增加 operand stack 使用量，必须让 AGP 重算被插桩方法的 frames。
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
            )
        }

        project.logger.lifecycle("APM SlowMethod Plugin applied with AGP instrumentation")
    }

    companion object {
        /** 插件扩展名。 */
        private const val EXTENSION_NAME = "apmSlowMethod"

        /** Android application 插件 id。 */
        private const val ANDROID_APPLICATION_PLUGIN_ID = "com.android.application"

        /** Android library 插件 id。 */
        private const val ANDROID_LIBRARY_PLUGIN_ID = "com.android.library"

        /** Android dynamic-feature 插件 id。 */
        private const val ANDROID_DYNAMIC_FEATURE_PLUGIN_ID = "com.android.dynamic-feature"
    }
}

/**
 * 慢方法插件配置扩展。
 */
open class ApmSlowMethodExtension {
    /** 需要排除的包名前缀列表（不插桩）。 */
    var excludePackages: List<String> = DEFAULT_EXCLUDE_PACKAGES

    /** 是否启用插件。 */
    var enabled: Boolean = true

    companion object {
        /** 默认排除的包名。 */
        private val DEFAULT_EXCLUDE_PACKAGES = listOf(
            "androidx.",
            "android.",
            "com.google.",
            "com.apm.slowmethod.",
            "kotlin.",
            "java.",
            "javax.",
            "dalvik."
        )
    }
}

/**
 * AGP instrumentation 参数。
 */
interface ApmSlowMethodInstrumentationParameters : InstrumentationParameters {
    /** 插件是否启用。 */
    @get:Input
    val enabled: Property<Boolean>

    /** 排除插桩的包名前缀列表。 */
    @get:Input
    val excludePackages: ListProperty<String>
}

/**
 * AGP ASM visitor 工厂。
 */
abstract class ApmSlowMethodAsmClassVisitorFactory :
    AsmClassVisitorFactory<ApmSlowMethodInstrumentationParameters> {

    /**
     * 创建每个 class 对应的 ASM visitor。
     *
     * @param classContext AGP class 上下文。
     * @param nextClassVisitor 下游 class visitor。
     * @return 注入慢方法探针的 visitor。
     */
    override fun createClassVisitor(classContext: com.android.build.api.instrumentation.ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        // 具体类名由 visitor.visit() 捕获，避免依赖 AGP 内部 class name 表示格式。
        return ApmClassTransformer.createClassVisitor(
            api = Opcodes.ASM9,
            classVisitor = nextClassVisitor
        )
    }

    /**
     * 判断当前 class 是否需要插桩。
     *
     * @param classData AGP 提供的 class 元数据。
     * @return true 表示该 class 会进入 ASM visitor。
     */
    override fun isInstrumentable(classData: ClassData): Boolean {
        val params = parameters.get()
        // 插件禁用或包名命中排除列表时，AGP 直接跳过该 class。
        return params.enabled.get() &&
            ApmClassTransformer.isInstrumentable(
                className = classData.className,
                excludePackages = params.excludePackages.get()
            )
    }
}
