# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Names are obfuscated in release builds. The mapping of each release is kept privately, outside
# the repository, to read the stack traces of crashes; file names are hidden, line numbers kept.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# The bootstrap and terminal JNI libraries bind their native methods by name: classes with native
# methods keep their names and those methods (proguard-android.txt). The RDP library also calls
# back into Java by method name, on the object given to nativeCreate.
-keep interface com.termux.app.terminow.rdp.TerminowRdpNative$Callbacks { *; }
-keepclassmembers class * implements com.termux.app.terminow.rdp.TerminowRdpNative$Callbacks {
    <methods>;
}

# TermuxUtils.getTermuxAppAPKClassField reads the fields of BuildConfig by name
-keep class com.termux.BuildConfig { *; }

# The settings screens are named in res/xml by app:fragment
-keep public class * extends androidx.preference.PreferenceFragmentCompat

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
