# JNI entry points are referenced by name from native code — keep them.
-keepclasseswithmembernames class com.amsynth.enhanced.AmsynthEngine {
    native <methods>;
}
-keep class com.amsynth.enhanced.AmsynthEngine { *; }
