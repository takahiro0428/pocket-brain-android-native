# Hilt (generated code & injected constructors)
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends androidx.hilt.** { *; }

# Kotlin metadata / Coroutines
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# AICore / Gemini generated request/response models use reflection for serialization
-keep class com.google.ai.edge.aicore.** { *; }
-keep class com.google.ai.client.generativeai.** { *; }

# Compose compiler attributes
-keep class androidx.compose.runtime.** { *; }
