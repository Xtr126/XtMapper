package android.view

import android.graphics.Point
import android.os.IInterface
import android.os.RemoteException

interface IWindowManager : IInterface {
    @Throws(RemoteException::class)
    fun getBaseDisplaySize(displayId: Int, size: Point)
}
