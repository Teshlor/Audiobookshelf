# kotlinx.serialization: the compiler plugin generates Companion.serializer() and $$serializer for every
# @Serializable class; R8 must not strip or rename them (we call Book.serializer() etc. directly).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class com.teshlor.abstv.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.teshlor.abstv.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.teshlor.abstv.**$$serializer { *; }
