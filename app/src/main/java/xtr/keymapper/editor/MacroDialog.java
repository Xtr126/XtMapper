package xtr.keymapper.editor;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import xtr.keymapper.R;
import xtr.keymapper.databinding.MacroDialogLayoutBinding;
import xtr.keymapper.databinding.MacroListItemBinding;
import xtr.keymapper.databinding.TextFieldBinding;
import xtr.keymapper.macro.MacroSharedPreferences;

public class MacroDialog {

    private final Context context;
    private final boolean useOverlayFlag;
    private final MacroSharedPreferences macroSharedPreferences;
    private final Set<String> macroIds;
    private AlertDialog dialog;


    public MacroDialog(Context context, boolean useOverlayFlag, Set<String> macroIds) {
        this.context = context;
        this.useOverlayFlag = useOverlayFlag;
        this.macroSharedPreferences = new MacroSharedPreferences(context);
        this.macroIds = macroIds;
    }

    public void show(View.OnClickListener l) {
        // Inflate the custom layout using View Binding
        // View Binding for custom_dialog_layout
        MacroDialogLayoutBinding dialogBinding = MacroDialogLayoutBinding.inflate(LayoutInflater.from(context));

        // Set up RecyclerView
        RecyclerView recyclerView = dialogBinding.recyclerView;
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        refreshRecyclerView(recyclerView);
        SharedPreferences.OnSharedPreferenceChangeListener listener = (sharedPreferences, key) -> refreshRecyclerView(recyclerView);
        macroSharedPreferences.registerOnSharedPreferenceChangeListener(listener);
        // Set up FloatingActionButton
        FloatingActionButton fab = dialogBinding.fab;
        fab.setOnClickListener(l);

        // Create the dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setView(dialogBinding.getRoot())
                .setTitle(R.string.macro)
                .setOnDismissListener(dialog1 -> macroSharedPreferences.unregisterOnSharedPreferenceChangeListener(listener));

        dialog = builder.create();

        setOverlayFlagAndShowDialog(dialog);
    }

    private void setOverlayFlagAndShowDialog(AlertDialog dialog) {
        // Set window flags if required
        if (useOverlayFlag) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            dialog.getWindow().setFormat(PixelFormat.TRANSLUCENT);
        }
        dialog.show();
    }

    private void refreshRecyclerView(RecyclerView recyclerView) {
        Set<String> dataSet = macroSharedPreferences.getMacroIds();
        List<String> dataList = new ArrayList<>(dataSet);
        RecyclerViewAdapter adapter = new RecyclerViewAdapter(dataList);
        recyclerView.setAdapter(adapter);
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    // RecyclerView Adapter with View Binding
    private class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder> {
        private final List<String> dataList;

        public RecyclerViewAdapter(List<String> dataList) {
            this.dataList = dataList;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Inflate the custom layout using View Binding
            MacroListItemBinding itemBinding = MacroListItemBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new ViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            // Bind data to the TextView
            String macroId = dataList.get(position);
            holder.binding.text1.setText(macroId);
            holder.binding.editButton.setOnClickListener(v -> {
                TextFieldBinding binding = TextFieldBinding.inflate(LayoutInflater.from(context));
                binding.getRoot().setHint(R.string.macro);
                binding.editText.setText(macroId);

                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
                AlertDialog dialog = builder.setPositiveButton(R.string.ok, (d, which) -> macroSharedPreferences.renameMacro(macroId, binding.editText.getText().toString()))
                        .setNegativeButton(R.string.cancel, (d, which) -> {})
                        .setView(binding.getRoot())
                        .create();
                setOverlayFlagAndShowDialog(dialog);
            });
            holder.binding.deleteButton.setOnClickListener(v -> macroSharedPreferences.removeMacro(macroId));

            holder.binding.toggle.setChecked(macroIds.contains(macroId));
            holder.binding.toggle.setOnCheckedChangeListener((t, checked) -> {
                if (checked) {
                    macroIds.add(macroId);
                } else {
                    macroIds.remove(macroId);
                }
            });
        }

        @Override
        public int getItemCount() {
            return dataList.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            MacroListItemBinding binding;

            public ViewHolder(@NonNull MacroListItemBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}