package android.app

import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException

interface IActivityManager : IInterface {
    @Throws(RemoteException::class)
    fun getTasks(maxNum: Int): List<ActivityManager.RunningTaskInfo>?

    @Throws(RemoteException::class)
    fun registerProcessObserver(observer: IProcessObserver)

    // shortcut: client lookup only; use an AIDL-generated Stub for service implementations.
    object Stub {
        @JvmStatic
        fun asInterface(obj: IBinder?): IActivityManager? {
            throw RuntimeException("STUB")
        }
    }
}
