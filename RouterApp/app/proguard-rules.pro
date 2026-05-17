# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt

# Keep Room entities
-keep class com.attendance.router.db.** { *; }

# Keep Gson model fields
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep enum values
-keepclassmembers enum * { *; }
