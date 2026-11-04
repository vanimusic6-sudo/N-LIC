# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class io.github.romanvht.byedpi.core.ByeDpiProxy { *; }

-keep,allowoptimization class io.github.romanvht.byedpi.core.TProxyService { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.activities.** { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.services.** { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.receiver.** { *; }

-keep class io.github.romanvht.byedpi.fragments.** {
    <init>();
}

-keep,allowoptimization class io.github.romanvht.byedpi.data.** {
    <fields>;
}

-keepattributes Signature
-keepattributes *Annotation*

-repackageclasses 'ru.romanvht'
-renamesourcefileattribute ''
-keepattributes SourceFile,InnerClasses,EnclosingMethod,Signature,RuntimeVisibleAnnotations,*Annotation*,*Parcelable*
-allowaccessmodification
-overloadaggressively
-optimizationpasses 5
-verbose
-dontusemixedcaseclassnames
-adaptclassstrings
-adaptresourcefilecontents **.xml,**.json
-adaptresourcefilenames **.xml,**.json
# The experimental libbox adapter is intentionally reflective. Core Preview
# must keep gomobile classes and interfaces (including method names).
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-keep class io.github.romanvht.byedpi.subscription.LibboxRuntime { *; }

# Xray gomobile JNI reflection
-keep class libv2ray.** { *; }
-keep class io.github.romanvht.byedpi.subscription.XrayRuntime { *; }

# JNI symbols are bound to the original Kotlin class/method names.
-keep class io.github.romanvht.byedpi.core.NlicV2Proxy { *; }
