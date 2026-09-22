# apm-slow-method 与 apm-plugin

> 同步日期：2026-09-22｜运行时模块：`slow_method`

## 三条观测路径

### Looper Printer Hook

`SlowMethodModule` 通过反射读取并包装 `Looper.mLogging`，根据 dispatch start/end 文本计算主线程消息耗时。反射失败时记录 internal error，不阻止应用运行。

### Stack sampling

检测到长 dispatch 后，`StackSamplingProfiler` 在 measurement-priority HandlerThread 中按 10ms 间隔、5s 窗口采样主线程 stack，统计 top 10 热点方法。

### ASM instrumentation

宿主必须应用 `com.apm.slow-method`。插件在 `apply()` 阶段通过 `pluginManager.withPlugin` 延迟注册：先应用 `com.apm.slow-method` 再应用 Android 插件（或相反）都生效，声明顺序不影响插桩；非 Android module 应用插件时在配置评估后给出 warn 诊断。

```text
AGP instrumentation scope PROJECT
  -> AsmClassVisitorFactory
  -> skip excluded/constructor/static-init/synthetic/abstract/native
  -> methodEnter(class#method+descriptor)
  -> normal return: methodExit
  -> propagated exception: catch-all handler -> methodExit -> ATHROW
```

只插桩当前 project，默认排除 Android/AndroidX/Kotlin/Java/APM tracer 包。排除项为包边界语义：不带尾点的 `com.example` 只匹配其包及子包，不会误排 `com.exampleevil.*`。AGP 重算被插桩方法 frames。

独立字节码转换路径（公开 API `transformJar`/`transformDirectory`）使用 `COMPUTE_FRAMES`；宿主类层次中的 `android.*` 不在插件 classpath，`getCommonSuperClass` 解析失败时回退保守的 `java/lang/Object`，不再静默返回原始字节。

## 运行时 tracer

`ApmSlowMethodTracer` 使用 ThreadLocal stack 配对 enter/exit，默认阈值 300ms，hot-method map 上限 100。事件：`slow_method_instrumented`。

Module 事件：`slow_method_detected`, `hot_methods_detected`。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| slow/severe | 300ms / 800ms |
| include stack | 开，最大 4000 chars |
| stack sampling | 开 |
| sample interval/window | 10ms / 5s |
| top methods | 10 |

## 线程与开销

Looper hook 回调在主线程，只做计时/触发；stack sampling 在独立 measurement thread。ASM 对每个被插桩方法增加 enter/exit 调用，必须通过包过滤和灰度控制开销。

## 边界

- 注册 `SlowMethodModule` 不会自动让所有方法 ASM 化；Gradle 插件是独立接线。
- Looper hook 只能定位 message dispatch 粒度。
- stack sampling 是统计近似，不保证命中真实最慢方法。
- 插件 transform 的工具方法在无法恢复的字节码损坏时仍返回原 class；帧计算的类解析失败已改为回退 `java/lang/Object`，AGP visitor 路径的失败表现由构建系统决定。

## 测试

Module/config/tracer 测试覆盖运行时；`apm-plugin` 独立测试验证过滤、方法签名、正常返回、显式/传播异常的 exit 平衡，以及插件延迟注册（无 Android 插件时安全）、不可解析宿主父类的帧回退插桩、排除包名的包边界语义。

## 时间语义

Looper、采样和 ASM tracer 的方法 duration 统一通过 `ApmClock` 单调时间计算；事件 timestamp 保持 epoch，系统校时不改变慢方法判定。
