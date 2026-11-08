# Add project specific ProGuard rules here.
# 目前 debug 构建不混淆；release 若开启混淆再补充 B 站模型/Media3 规则。

# ---- kotlinx.serialization（归档/JSON 导入） ----
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-dontwarn kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.wonderlandkid.biliaudio.**$$serializer { *; }
-keepclassmembers class com.wonderlandkid.biliaudio.** { *** Companion; }
-keepclasseswithmembers class com.wonderlandkid.biliaudio.** { kotlinx.serialization.KSerializer serializer(...); }

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ---- OkHttp 可选依赖告警 ----
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
