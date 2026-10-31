# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-repackageclasses
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile

# Keep Xposed entry point
-keep class moe.chenxy.oppopods.hook.HookEntry { *; }

# Keep all hooker classes (referenced by name in Xposed framework)
-keep class moe.chenxy.oppopods.hook.** { *; }

# Keep Parcelable data classes (used in broadcast extras)
-keep class moe.chenxy.oppopods.utils.miuiStrongToast.data.** { *; }
