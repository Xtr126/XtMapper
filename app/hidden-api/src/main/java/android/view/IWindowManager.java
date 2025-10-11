package android.view;

import android.os.IInterface;

public interface IWindowManager extends IInterface {
    public void getBaseDisplaySize(int displayId, android.graphics.Point size) throws android.os.RemoteException;
}