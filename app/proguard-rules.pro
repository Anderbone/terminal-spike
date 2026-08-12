# JSch resolves authentication and algorithm implementations from configurable class-name
# strings. Keep the standard-JCA families used by this app; optional Bouncy Castle and GSSAPI
# implementations remain shrinkable because those providers/authentication modes are not bundled.
-keep,allowoptimization class com.jcraft.jsch.UserAuthNone { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPassword { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthKeyboardInteractive { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPublicKey { *; }
-keep,allowoptimization class com.jcraft.jsch.CipherNone { *; }
-keep,allowoptimization class com.jcraft.jsch.DH* { *; }
-keep,allowoptimization class com.jcraft.jsch.jce.** { *; }
-keep,allowoptimization class com.jcraft.jsch.jbcrypt.** { *; }
-keep,allowoptimization class com.jcraft.jsch.jzlib.** { *; }

# GeneratedMessageLite reflects the raw field names encoded in AppSettings' message info.
-keepclassmembers,allowoptimization class com.yanjiyu.terminalspike.core.data.settings.AppSettings {
    <fields>;
}
