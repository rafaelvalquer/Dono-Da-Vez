package app.donodavez.core

/** Configuração pública por ambiente (BuildConfig do app). Nunca contém segredos. */
data class AppConfig(
    val environment: String,
    val supabaseUrl: String,
    val publishableKey: String,
    /** Vazio = estilo base embutido (somente ruas desenhadas pelo app; uso local). */
    val mapStyleUrl: String,
    val mapPublicKey: String,
    /** GPS simulado só é aceito em builds local/homologação; o servidor decide de novo. */
    val allowMockLocation: Boolean,
    val appVersionCode: Int,
    val appVersionName: String,
) {
    val apiBaseUrl: String get() = "${supabaseUrl.trimEnd('/')}/functions/v1/api"
    val authBaseUrl: String get() = "${supabaseUrl.trimEnd('/')}/auth/v1"
    val authRedirectUrl: String get() = "donodavez://auth-callback"
}
