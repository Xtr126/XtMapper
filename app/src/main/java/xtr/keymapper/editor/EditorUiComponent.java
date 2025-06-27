package xtr.keymapper.editor;

import android.content.Context;
import android.view.LayoutInflater;

public abstract class EditorUiComponent {
    private final EditorUiComponentCallback mCallback;
    private final Context context;

    public EditorUiComponent(EditorUiComponentCallback callback, Context context, float x, float y) {
        this.mCallback = callback;
        this.context = context;
    }

    protected Context getContext() {
        return context;
    }

    protected LayoutInflater getLayoutInflater() {
        return context.getSystemService(LayoutInflater.class);
    }

    protected EditorUiComponentCallback getCallback(){
        return mCallback;
    }

    public abstract String getDataLine();
}
