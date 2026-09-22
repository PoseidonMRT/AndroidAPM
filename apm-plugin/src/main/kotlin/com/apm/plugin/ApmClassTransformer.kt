package com.apm.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Label
import org.objectweb.asm.commons.AdviceAdapter
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * ASM 类转换器。
 * 负责读取 class 文件，通过 ASM 字节码操作注入方法计时逻辑。
 *
 * ## 插桩逻辑
 * - 在方法入口注入：ApmSlowMethodTracer.methodEnter(className#methodName)
 * - 在方法出口注入：ApmSlowMethodTracer.methodExit(className#methodName)
 * - 运行时 Tracer 自动配对 enter/exit 计算方法耗时
 */
object ApmClassTransformer {

    /** 运行时 Tracer 类的全限定名（JVM 内部格式）。 */
    private const val TRACER_CLASS = "com/apm/slowmethod/ApmSlowMethodTracer"
    /** Tracer 的 methodEnter 方法签名。 */
    private const val METHOD_ENTER = "methodEnter"
    /** Tracer 的 methodExit 方法签名。 */
    private const val METHOD_EXIT = "methodExit"
    /** 方法签名描述符。 */
    private const val METHOD_DESC = "(Ljava/lang/String;)V"
    /** 帧计算无法解析宿主类层次时的保守公共超类型（JVM 内部格式）。 */
    private const val JAVA_LANG_OBJECT = "java/lang/Object"
    /** 点分包名分隔符。 */
    private const val PACKAGE_SEPARATOR = '.'

    /**
     * 创建可复用的 ASM ClassVisitor。
     *
     * @param api ASM API 版本。
     * @param classVisitor 下游 visitor。
     * @param className 可选类名；为空时从 visit 回调中读取。
     * @return 注入慢方法探针的 visitor。
     */
    fun createClassVisitor(api: Int, classVisitor: org.objectweb.asm.ClassVisitor, className: String? = null): org.objectweb.asm.ClassVisitor {
        return ApmClassVisitor(
            api = api,
            classVisitor = classVisitor,
            className = className.orEmpty()
        )
    }

    /**
     * 判断类是否应参与插桩。
     *
     * 排除项采用包边界语义：不带尾点的排除项只匹配其包及子包，
     * 避免 "com.example" 误排 "com.exampleevil.*" 这类共享前缀的无关包。
     *
     * @param className 点分、斜杠或 class 文件路径格式类名。
     * @param excludePackages 排除包名前缀。
     * @return true 表示可插桩。
     */
    fun isInstrumentable(className: String, excludePackages: List<String>): Boolean {
        val normalizedClassName = className
            .removeSuffix(".class")
            .replace('/', '.')
        return excludePackages.none { exclude -> matchesExclude(normalizedClassName, exclude) }
    }

    /**
     * 单个排除项是否命中目标类名。
     *
     * @param normalizedClassName 已归一化为点分格式的类名。
     * @param exclude 排除项，可带或不带尾点。
     * @return true 表示该类被此排除项命中。
     */
    private fun matchesExclude(normalizedClassName: String, exclude: String): Boolean {
        if (!normalizedClassName.startsWith(exclude)) {
            return false
        }
        // 带尾点的排除项维持历史前缀语义；不带尾点必须命中包边界才排除。
        return exclude.endsWith(PACKAGE_SEPARATOR) ||
            exclude.length == normalizedClassName.length ||
            normalizedClassName[exclude.length] == PACKAGE_SEPARATOR
    }

    /**
     * 转换 jar 文件。
     * 遍历 jar 中的每个 class 文件，符合条件的进行插桩。
     *
     * @param inputJar 输入 jar 文件。
     * @param outputJar 输出 jar 文件。
     * @param config 插件配置。
     */
    fun transformJar(inputJar: File, outputJar: File, config: ApmSlowMethodExtension) {
        JarFile(inputJar).use { jarFile ->
            JarOutputStream(FileOutputStream(outputJar)).use { jos ->
                val entries = jarFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // 读取原始字节
                    val bytes = jarFile.getInputStream(entry).readBytes()

                    // 判断是否需要插桩
                    val transformed = if (entry.name.endsWith(".class")) {
                        transformClass(bytes, entry.name, config)
                    } else {
                        bytes
                    }

                    // 写入输出 jar
                    val newEntry = JarEntry(entry.name)
                    jos.putNextEntry(newEntry)
                    jos.write(transformed)
                    jos.closeEntry()
                }
            }
        }
    }

    /**
     * 转换目录中的所有 class 文件。
     *
     * @param inputDir 输入目录。
     * @param outputDir 输出目录。
     * @param config 插件配置。
     */
    fun transformDirectory(inputDir: File, outputDir: File, config: ApmSlowMethodExtension) {
        outputDir.mkdirs()
        inputDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(inputDir).path
                val outputFile = File(outputDir, relativePath)
                outputFile.parentFile?.mkdirs()

                if (file.name.endsWith(".class")) {
                    val bytes = file.readBytes()
                    val transformed = transformClass(bytes, relativePath, config)
                    outputFile.writeBytes(transformed)
                } else {
                    file.copyTo(outputFile, overwrite = true)
                }
            }
        }
    }

    /**
     * 转换单个 class 字节码。
     * 使用 ASM ClassReader/ClassWriter 进行字节码操作。
     *
     * @param bytes 原始 class 字节码。
     * @param className 类文件路径。
     * @param config 插件配置。
     * @return 插桩后的字节码。
     */
    private fun transformClass(bytes: ByteArray, className: String, config: ApmSlowMethodExtension): ByteArray {
        if (!config.enabled) {
            return bytes
        }
        // 将路径格式转为类名格式：com/example/MyClass.class → com.example.MyClass
        val fullClassName = className
            .removeSuffix(".class")
            .replace('/', '.')

        // 排除不需要插桩的类
        if (!isInstrumentable(fullClassName, config.excludePackages)) {
            return bytes
        }

        try {
            val classReader = ClassReader(bytes)
            val classWriter = object : ClassWriter(
                classReader,
                ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES
            ) {
                /**
                 * 帧计算解析公共超类型时的安全回退。
                 *
                 * ASM 默认实现用插件类加载器加载类；宿主类层次中的 android.* / androidx.*
                 * 不在插件 classpath，解析会抛异常。帧计算只需保守的公共超类型，
                 * 回退 java/lang/Object 即可保证输出字节码可验证，避免整个类被静默跳过。
                 */
                override fun getCommonSuperClass(type1: String, type2: String): String {
                    return try {
                        super.getCommonSuperClass(type1, type2)
                    } catch (error: Exception) {
                        JAVA_LANG_OBJECT
                    } catch (error: LinkageError) {
                        JAVA_LANG_OBJECT
                    }
                }
            }

            val visitor = ApmClassVisitor(
                api = Opcodes.ASM9,
                classVisitor = classWriter,
                className = fullClassName
            )

            classReader.accept(visitor, ClassReader.EXPAND_FRAMES)
            return classWriter.toByteArray()
        } catch (e: Exception) {
            // ASM 插桩失败时返回原始字节码，避免编译中断
            return bytes
        }
    }

    /**
     * ASM ClassVisitor 实现。
     * 访问类中的每个方法，委托给 ApmMethodVisitor 进行插桩。
     */
    private class ApmClassVisitor(
        api: Int,
        classVisitor: org.objectweb.asm.ClassVisitor,
        /** 当前类名（点分格式）。 */
        private var className: String
    ) : org.objectweb.asm.ClassVisitor(api, classVisitor) {

        /**
         * 访问 class 头部并捕获当前类名。
         */
        override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<String>?) {
            // AGP instrumentation 场景下从 class visit 回调捕获内部类名。
            if (className.isBlank() && name != null) {
                className = name.replace('/', '.')
            }
            super.visit(version, access, name, signature, superName, interfaces)
        }

        /**
         * 访问方法并为可插桩方法创建 AdviceAdapter。
         */
        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            // 跳过构造函数、静态初始化块和合成的 acc\$ 方法
            if (name == "<init>" || name == "<clinit>" || name.contains("$")) {
                return mv
            }
            // 跳过 abstract/native 方法（无方法体）
            if (access and Opcodes.ACC_ABSTRACT != 0 || access and Opcodes.ACC_NATIVE != 0) {
                return mv
            }
            return ApmMethodVisitor(
                api = api,
                methodVisitor = mv,
                access = access,
                name = name,
                descriptor = descriptor,
                className = className
            )
        }
    }

    /**
     * ASM MethodVisitor 实现。
     * 使用 AdviceAdapter 在方法入口和出口注入计时代码。
     *
     * 插桩效果：
     * ```java
     * void someMethod() {
     *     ApmSlowMethodTracer.methodEnter("com.example.MyClass#someMethod");
     *     try {
     *         // 原始方法体
     *     } finally {
     *         ApmSlowMethodTracer.methodExit("com.example.MyClass#someMethod");
     *     }
     * }
     * ```
     */
    private class ApmMethodVisitor(
        api: Int,
        methodVisitor: MethodVisitor,
        access: Int,
        /** 方法名。 */
        private val name: String,
        descriptor: String,
        /** 所属类名。 */
        private val className: String
    ) : AdviceAdapter(api, methodVisitor, access, name, descriptor) {

        /** 方法签名（className#methodName+descriptor 格式）。 */
        private val methodSignature = "$className#$name$descriptor"

        /** Start of the method body protected by the catch-all handler. */
        private val protectedStart = Label()

        /** End of the protected method body. */
        private val protectedEnd = Label()

        /** Catch-all handler that balances the tracer stack. */
        private val exceptionHandler = Label()

        override fun onMethodEnter() {
            super.onMethodEnter()
            // 注入：ApmSlowMethodTracer.methodEnter("className#methodName")
            mv.visitLdcInsn(methodSignature)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                TRACER_CLASS,
                METHOD_ENTER,
                METHOD_DESC,
                false
            )
            mv.visitLabel(protectedStart)
        }

        override fun onMethodExit(opcode: Int) {
            super.onMethodExit(opcode)
            // Explicit and propagated exceptions are balanced by the catch-all handler.
            if (opcode == Opcodes.ATHROW) {
                return
            }
            emitMethodExit()
        }

        /** Emits one tracer exit call. */
        private fun emitMethodExit() {
            // 注入：ApmSlowMethodTracer.methodExit("className#methodName")
            mv.visitLdcInsn(methodSignature)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                TRACER_CLASS,
                METHOD_EXIT,
                METHOD_DESC,
                false
            )
        }

        /**
         * Adds a catch-all handler so exceptions propagated from callees still
         * balance the ThreadLocal method stack.
         */
        override fun visitMaxs(maxStack: Int, maxLocals: Int) {
            mv.visitLabel(protectedEnd)
            mv.visitTryCatchBlock(protectedStart, protectedEnd, exceptionHandler, null)
            mv.visitLabel(exceptionHandler)
            emitMethodExit()
            mv.visitInsn(Opcodes.ATHROW)
            super.visitMaxs(maxStack, maxLocals)
        }
    }
}
