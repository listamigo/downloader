# Add project specific ProGuard rules here.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep class com.elimd.downloader.core.database.** { *; }
-keep class com.elimd.downloader.core.di.** { *; }
-keep class com.elimd.downloader.domain.model.** { *; }
-keep class com.elimd.downloader.data.repository.** { *; }
