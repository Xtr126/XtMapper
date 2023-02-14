package xtr.keymapper.profiles;

import android.app.AlertDialog;
import android.content.Context;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.view.ContextThemeWrapper;

import java.util.ArrayList;

import xtr.keymapper.R;
import xtr.keymapper.TouchPointer;

public class ProfileSelector {
    private String selectedProfile;

    public interface OnProfileSelectedListener {
        void onProfileSelected(String profile);
    }

    public ProfileSelector(Context context, OnProfileSelectedListener listener){
        ArrayList<String> allProfiles = new ArrayList<>(new KeymapProfiles(context).getAllProfiles().keySet());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.select_dialog_singlechoice, allProfiles);

        AlertDialog dialog;
        AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(context, R.style.Theme_Material3_DayNight_Dialog_Alert));

        // Show dialog to select profile
        if (!adapter.isEmpty())
            builder.setTitle(R.string.dialog_alert_select_profile)
                    .setSingleChoiceItems(adapter, -1,
                            (d, which) -> selectedProfile = allProfiles.get(which))
                    .setPositiveButton("ok", (d, which) -> {
                        if (selectedProfile != null)
                            listener.onProfileSelected(selectedProfile);
                        else Toast.makeText(context, R.string.no_profile, Toast.LENGTH_SHORT).show();
                    });
        else { // Create profile if no profile found
            EditText editText = new EditText(context);
            builder.setMessage(R.string.dialog_alert_add_profile)
                    .setPositiveButton("ok", (d, which) -> {
                        selectedProfile = editText.getText().toString();
                        KeymapProfiles keymapProfiles = new KeymapProfiles(context);
                        keymapProfiles.saveProfile(selectedProfile, new ArrayList<>(), context.getPackageName());
                        listener.onProfileSelected(selectedProfile);
                    })
                    .setNegativeButton("cancel", (d, which) -> {})
                    .setView(editText);
        }
        dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }
}
