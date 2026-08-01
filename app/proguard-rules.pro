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

-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedElement
