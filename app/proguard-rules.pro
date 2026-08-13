# Keep VLC native bridge classes
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.libvlc.**$* { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
