package app.nouri

import android.app.Application
import app.nouri.work.Maintenance

class NouriApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
        Maintenance.schedule(this)
    }
}
