# kotlinx.serialization 依赖反射查找 $$serializer，R8 不保留就会在运行时抛
# SerializationException: Serializer for class 'X' is not found
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 本项目的所有 @Serializable 数据类
-keep,includedescriptorclasses class com.liteagent.**$$serializer { *; }
-keepclassmembers class com.liteagent.** {
    *** Companion;
}
-keepclasseswithmembers class com.liteagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNI：native 方法名是按包名生成的，混淆包名会让 System.loadLibrary 后找不到符号
-keep class com.liteagent.app.engine.LlmEngine { *; }
-keep class com.liteagent.app.data.ModelEntry { *; }
-keep class com.liteagent.app.data.ModelFile { *; }

# JNI：回调方法也是按名字查找的。
# native 侧用 env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z")，
# R8 一旦把这个方法改名，GetMethodID 返回 null —— 表现是"模型加载成功但一个字都不出"，
# release 包静默失效，debug 完全正常。这一条是实测踩出来的。
-keepclassmembers class * {
    boolean onToken(java.lang.String);
}
