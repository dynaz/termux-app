# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# JSch, for SFTP in the File Explorer, creates its ciphers, key exchanges and signatures by class
# name from its configuration, so R8 can not see that they are used.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**

# YubiKit (FIDO2 security keys of the keychain): a compile-time FindBugs annotation, and its
# classes kept whole, since its sessions and CBOR are not tested shrunk on devices
-dontwarn edu.umd.cs.findbugs.annotations.**
-keep class com.yubico.yubikit.** { *; }
