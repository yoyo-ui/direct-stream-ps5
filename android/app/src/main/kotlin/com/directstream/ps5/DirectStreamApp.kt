package com.directstream.ps5

import android.app.Application
import com.directstream.ps5.data.CredentialStore
import com.directstream.ps5.data.JobStore
import com.directstream.ps5.data.SettingsRepository
import com.directstream.ps5.transfer.TransferManager

/** Manual dependency wiring: three singletons are all this app needs. */
class DirectStreamApp : Application() {
    lateinit var secrets: CredentialStore; private set
    lateinit var settings: SettingsRepository; private set
    lateinit var store: JobStore; private set
    val manager: TransferManager by lazy { TransferManager(this, store, settings, secrets) }

    override fun onCreate() {
        super.onCreate()
        secrets = CredentialStore(this)
        settings = SettingsRepository(this, secrets)
        store = JobStore(this)
        manager   // start recovery and network watching immediately
    }

    companion object { fun from(app: Application) = app as DirectStreamApp }
}
