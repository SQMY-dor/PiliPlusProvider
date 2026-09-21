# libxposed
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 模块代码（HookEntry / App / MainActivity 等）
-keep class io.github.piliplusprovider.** { *; }

# Lyricon Provider
-keep class io.github.proify.lyricon.** { *; }

# 星河岛接入库（AAR）。广播接收器由系统按类名实例化，必须保留；
# 客户端库内部使用固定标识与操作编号，官方说明无需额外保留规则，
# 但接收器与投送端是我们的代码，需要保留以避免被混淆改名。
-keep class com.astraisland.** { *; }
-keep class io.github.piliplusprovider.island.** { *; }

-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedElement
