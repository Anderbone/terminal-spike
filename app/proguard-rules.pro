# JSch resolves authentication and algorithm implementations from configurable class-name
# strings. Keep the standard-JCA families used by this app plus the narrow Bouncy Castle Ed25519
# path bundled for Android; unrelated optional provider and GSSAPI implementations remain
# shrinkable.
-keep,allowoptimization class com.jcraft.jsch.UserAuthNone { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPassword { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthKeyboardInteractive { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPublicKey { *; }
-keep,allowoptimization class com.jcraft.jsch.CipherNone { *; }
-keep,allowoptimization class com.jcraft.jsch.DH* { *; }
-keep,allowoptimization class com.jcraft.jsch.jce.** { *; }
-keep,allowoptimization class com.jcraft.jsch.jbcrypt.** { *; }
-keep,allowoptimization class com.jcraft.jsch.jzlib.** { *; }
-keep,allowoptimization class com.jcraft.jsch.bc.KeyPairGenEdDSA { *; }
-keep,allowoptimization class com.jcraft.jsch.bc.SignatureEdDSA { *; }
-keep,allowoptimization class com.jcraft.jsch.bc.SignatureEd25519 { *; }

# GeneratedMessageLite reflects the raw field names encoded in AppSettings' message info.
-keepclassmembers,allowoptimization class com.yanjiyu.terminalspike.core.data.settings.AppSettings {
    <fields>;
}
