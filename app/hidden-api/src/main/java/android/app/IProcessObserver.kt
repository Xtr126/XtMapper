package android.app

import android.os.Binder
import android.os.RemoteException

interface IProcessObserver {
    @Throws(RemoteException::class)
    fun onForegroundActivitiesChanged(pid: Int, uid: Int, foregroundActivities: Boolean)

    @Throws(RemoteException::class)
    fun onForegroundServicesChanged(pid: Int, uid: Int, serviceTypes: Int)

    @Throws(RemoteException::class)
    fun onProcessDied(pid: Int, uid: Int)

    abstract class Stub : Binder(), IProcessObserver
}
