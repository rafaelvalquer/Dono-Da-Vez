# kotlinx.serialization, OkHttp, MapLibre e Firebase publicam suas próprias regras (consumer rules).
# Mantém nomes de DTOs serializáveis para mensagens de erro legíveis em relatórios de falha.
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class app.donodavez.**$$serializer { *; }
-keepclassmembers class app.donodavez.** {
    *** Companion;
}
-keepclasseswithmembers class app.donodavez.** {
    kotlinx.serialization.KSerializer serializer(...);
}
