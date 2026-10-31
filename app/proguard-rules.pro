# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---- Kotlin / Coroutines ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- 数据模型（Room 反射兜底）----
-keep class com.foldledger.data.db.** { *; }
-keep class com.foldledger.data.model.** { *; }

# ---- 保留行号，方便定位崩溃 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- JNI 原生桥（类名与方法名必须保留，否则 Rust .so 的 JNI 查找会抛 UnsatisfiedLinkError） ----
-keep class com.foldledger.core.DripCore { *; }
