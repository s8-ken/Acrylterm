package com.shibaken.androidterminal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.shibaken.androidterminal.data.HostRepository
import com.shibaken.androidterminal.ext.ScriptStore
import com.shibaken.androidterminal.data.KnownHostsRepository
import com.shibaken.androidterminal.keys.KeyRepository
import com.shibaken.androidterminal.session.SessionManager
import com.shibaken.androidterminal.tailscale.TailscaleManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

class App : Application() {

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        installBouncyCastle()
        createNotificationChannels()

        hosts = HostRepository(this)
        knownHosts = KnownHostsRepository(this)
        keys = KeyRepository(this)
        tailscale = TailscaleManager(this)
        scriptStore = ScriptStore(File(filesDir, "ext"))
        sessions = SessionManager(this)
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
            NotificationChannel(CHANNEL_SESSION, "接続中のセッション", NotificationManager.IMPORTANCE_LOW).apply {
                description = "SSH 接続をバックグラウンドで維持している間に表示されます"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_AGENT, "エージェントの状態", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "エージェントが入力待ちや完了になったときに通知します"
            }
        )
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_AGENT = "herdr_agent"

        lateinit var instance: App
            private set
    }
}
