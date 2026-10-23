# Keep serialization metadata for the protocol DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class io.github.xechoz.offlineweb.internal.protocol.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.github.xechoz.offlineweb.internal.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
