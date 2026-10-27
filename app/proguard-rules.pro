# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class dev.shutchat.app.** {
    *** Companion;
}
-keepclasseswithmembers class dev.shutchat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.shutchat.app.**$$serializer { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Google Nearby Connections uses reflection for its callbacks.
-keep class com.google.android.gms.nearby.** { *; }
-dontwarn com.google.android.gms.**
