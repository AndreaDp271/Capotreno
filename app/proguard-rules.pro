# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.andreadp.trenolibero.**$$serializer { *; }
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# pdfbox-android (decoder JPEG2000 opzionale non incluso)
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# Widget Glance: le azioni sono istanziate per nome di classe
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Ogni widget deve restare una classe distinta: Glance cerca i widget per classe
# (getGlanceIds) e R8 altrimenti fonde le sottoclassi simili in una sola.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }
