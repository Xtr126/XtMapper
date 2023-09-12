// ActivityObserverCallback.aidl
package xtr.keymapper;

// Declare any non-default types here with import statements

interface ActivityObserver {
    void onForegroundActivitiesChanged(String packageName);
}