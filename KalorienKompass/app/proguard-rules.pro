# Release builds are not minified (isMinifyEnabled = false), so no rules are required.
# Kept for completeness in case minification is enabled later.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class de.kalorienkompass.** {
    *** Companion;
}
-keepclasseswithmembers class de.kalorienkompass.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Anthropic SDK (Jackson based)
-keep class com.anthropic.** { *; }
-dontwarn com.anthropic.**
-dontwarn com.fasterxml.jackson.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
