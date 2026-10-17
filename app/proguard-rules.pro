# ProGuard rules for AI Agent
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
