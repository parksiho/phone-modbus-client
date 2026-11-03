# R8 release. AdMob/UMP AAR 의 consumer rules 도 함께 적용된다.
# GMS 가 리플렉션으로 찾는 진입점과 UMP proto 필드를 릴리스에서 지우지 않는다.

-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }
-keep class com.google.android.gms.internal.consent_sdk.** { *; }

-keep class * implements com.google.android.gms.ads.mediation.MediationAdapter { public *; }
-keep class * extends com.google.android.gms.ads.mediation.Adapter { public *; }
-keep class com.google.android.gms.ads.internal.util.WorkManagerUtil { public *; }
-keep class com.google.android.gms.ads.internal.offline.buffering.OfflineNotificationPoster { public *; }
-keep class com.google.android.gms.ads.internal.offline.buffering.OfflinePingSender { public *; }
-keep class com.google.android.gms.ads.internal.client.LiteSdkInfo { public *; }

-keepclassmembers class * extends com.google.android.gms.internal.ads.zzihc { <fields>; }
-keepclassmembers class * extends com.google.android.gms.internal.consent_sdk.zzqm { <fields>; }

-dontwarn com.google.android.gms.**
-dontwarn com.google.android.ump.**
