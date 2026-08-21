# AgroVisión — reglas de R8 (modo full, §12.7)
#
# Una sola fuente de verdad: ninguna biblioteca del proyecto declara reglas de
# consumidor. Así, cuando algo se rompe en release y funciona en debug, hay un
# solo archivo que mirar.

# ─────────────────────────────────────────────────────────────────────────────
# Clave pública de firma de modelos
#
# Va en BuildConfig como constante de cadena. R8 la conserva porque se usa, pero
# se documenta aquí porque es tentador «limpiar» BuildConfig: sin ella la
# verificación Ed25519 no puede ocurrir y NINGÚN modelo se activaría.
# ─────────────────────────────────────────────────────────────────────────────
-keep class co.agrovision.BuildConfig { *; }

# ─────────────────────────────────────────────────────────────────────────────
# kotlinx.serialization
#
# El plugin genera `serializer()` por reflexión de nombre. Sin estas reglas, los
# DTO se ofuscan y el parseo falla SOLO en release — el peor modo de fallo
# posible, porque las pruebas pasan.
# ─────────────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ─────────────────────────────────────────────────────────────────────────────
# LiteRT / TensorFlow Lite
#
# Los delegates se cargan por reflexión desde código nativo. Ofuscarlos produce
# un `UnsatisfiedLinkError` en el arranque del intérprete, es decir: la app se
# cierra al abrir la cámara, solo en release.
# ─────────────────────────────────────────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**

# ─────────────────────────────────────────────────────────────────────────────
# BouncyCastle
#
# Solo se usa Ed25519. El resto —y es la mayor parte del artefacto— se elimina.
# Sin `dontwarn`, R8 falla por referencias a APIs de JSSE que no existen en
# Android.
# ─────────────────────────────────────────────────────────────────────────────
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.crypto.params.Ed25519PublicKeyParameters { *; }
-dontwarn org.bouncycastle.jsse.**
-dontwarn javax.naming.**

# ─────────────────────────────────────────────────────────────────────────────
# Room, Hilt, WorkManager, Retrofit, OkHttp
# ─────────────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking @kotlin.Metadata class *
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ─────────────────────────────────────────────────────────────────────────────
# Trazas útiles en Crashlytics
#
# Sin esto, un cierre en producción llega como una pila de `a.b.c(:1)` y no hay
# forma de saber qué falló en un teléfono al que no se tiene acceso.
# ─────────────────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─────────────────────────────────────────────────────────────────────────────
# Registro
#
# Se eliminan verbose y debug de release. `Log.w` y `Log.e` SE CONSERVAN: son lo
# que aparece en el informe de fallo, y quitarlos deja los incidentes ciegos.
# ─────────────────────────────────────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
