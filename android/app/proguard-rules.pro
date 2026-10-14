# ============================================================ GLP Mate 混淆规则
#
# 目标：把 debug 版 37MB 压下来，同时**不弄坏 Chaquopy 的 Python 集成**。
# Chaquopy 靠 JNI 和反射从 Java 调 Python（反过来也一样），
# 被混淆掉类名/方法名就会在运行时炸——而且往往只在真机上炸，编译期看不出来。

# ---------------------------------------------------------------- Chaquopy
# Chaquopy 的 Java 侧入口与 JNI 桥接全部保留。
# 官方文档要求保留 com.chaquo.python 整个包。
-keep class com.chaquo.python.** { *; }
-keep interface com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Python 通过 jclass/反射访问的类也要保留（本项目的 store/coach 等不直接暴露给
# Python，但 MainActivity 里的 Python 启动入口与回调必须留）。
-keep class com.glpmate.native_app.MainActivity { *; }
-keepclassmembers class * {
    @com.chaquo.python.PyObject *;
}

# ---------------------------------------------------------------- JNI 通用
# native 方法名与调用它的类名都不能改（JNI 按名字查找）。
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------- Compose
# Compose 的运行时靠反射调用 @Composable，保留注解信息。
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ---------------------------------------------------------------- 序列化/JSON
# 应用用 org.json 手工解析后端返回，没有反射式绑定，不需要额外 keep。
# 但保留泛型签名，避免 JSON 相关的泛型桥接方法被剥掉。
-keepattributes Signature, InnerClasses, EnclosingMethod

# ---------------------------------------------------------------- 通知/闹钟
# ReminderScheduler 与 BroadcastReceiver 由系统按类名实例化，不能混淆。
-keep class com.glpmate.native_app.notify.** { *; }
-keep class * extends android.content.BroadcastReceiver { *; }

# ---------------------------------------------------------------- 其它
# 去掉日志（正式包不要把调试日志带到用户机器上；敏感信息只写文件不写日志，
# 但行号与类名仍会暴露实现细节）。保留 GlpFood 这类诊断标签会泄露文件路径。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# 保留行号，便于用户报错时定位（体积代价很小）。
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
