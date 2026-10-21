package com.shibaken.androidterminal

import android.app.Application
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.shibaken.androidterminal.data.AppSettingsRepository
import com.shibaken.androidterminal.data.AppearanceRepository
import com.shibaken.androidterminal.ui.CyanVeil
import com.shibaken.androidterminal.terminal.CyanVeilTerminalPalette
import android.app.NotificationChannel
import android.app.NotificationManager
import com.shibaken.androidterminal.data.HostRepository
import com.shibaken.androidterminal.ext.ExtTemplates
import com.shibaken.androidterminal.ext.ScriptStore
import com.shibaken.androidterminal.data.KnownHostsRepository
import com.shibaken.androidterminal.keys.KeyRepository
import com.shibaken.androidterminal.session.SessionManager
import com.shibaken.androidterminal.tailscale.TailscaleManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

class App : Application() {

    lateinit var appearance: AppearanceRepository
        private set
    lateinit var settings: AppSettingsRepository
        private set
    lateinit var hosts: HostRepository
        private set
    lateinit var knownHosts: KnownHostsRepository
        private set
    lateinit var keys: KeyRepository
        private set
    lateinit var sessions: SessionManager
        private set
    lateinit var tailscale: TailscaleManager
        private set
    lateinit var scriptStore: ScriptStore
        private set

    /** 同梱の拡張テンプレート(assets は不変なので初回に読んで使い回す)。 */
    val extTemplates: List<ExtTemplates.Template> by lazy { ExtTemplates.list(assets) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installBouncyCastle()
        createNotificationChannels()

        appearance = AppearanceRepository(this)
        val startPalette = appearance.settings.value.colors.resolve()
        CyanVeil.palette = startPalette
        CyanVeilTerminalPalette.install(startPalette)
        hosts = HostRepository(this)
        knownHosts = KnownHostsRepository(this)
        keys = KeyRepository(this)
        tailscale = TailscaleManager(this)
        scriptStore = ScriptStore(File(filesDir, "ext"))
        settings = AppSettingsRepository(this) {
            hosts.hosts.value.any { it.extEnabled } || scriptStore.listGlobal().isNotEmpty()
        }
        sessions = SessionManager(this)
    }

    /** Saves [settings] and switches the live UI palette and terminal colors to the selected profile. */
    fun applyAppearance(settings: com.shibaken.androidterminal.data.AppearanceSettings) {
        appearance.save(settings)
        val palette = settings.colors.resolve()
        CyanVeil.palette = palette
        CyanVeilTerminalPalette.install(palette)
        sessions.refreshTerminalColors()
    }

    /**
     * Android ships a stripped-down provider registered as "BC". sshj (and our key generator)
     * need the full BouncyCastle, so replace it.
     */
    private fun installBouncyCastle() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, getString(R.string.core_channel_session), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.core_channel_session_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_AGENT, getString(R.string.core_channel_agent), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.core_channel_agent_desc)
            }
        )
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_AGENT = "herdr_agent"

        lateinit var instance: App
            private set

        /** Resolve a string resource from non-UI code. Falls back to the resource id in JVM unit tests (no Application). */
        fun str(@StringRes id: Int, vararg args: Any): String =
            if (::instance.isInitialized) instance.getString(id, *args) else "string/$id"

        fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): String =
            if (::instance.isInitialized) instance.resources.getQuantityString(id, quantity, *args) else "plural/$id"
    }
}
