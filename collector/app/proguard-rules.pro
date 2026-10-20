# kotlinx.serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class io.github.schwi2807.atlas.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.schwi2807.atlas.**$$serializer { *; }
# MapLibre uses JNI
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
