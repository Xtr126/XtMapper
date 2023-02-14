package xtr.keymapper.profiles;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;

import xtr.keymapper.R;
import xtr.keymapper.databinding.FragmentProfilesViewBinding;

public class ProfilesViewFragment extends Fragment {
    private FragmentProfilesViewBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        binding = FragmentProfilesViewBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context context = view.getContext();
        Drawable profilesShow = AppCompatResources.getDrawable(context, R.drawable.ic_profiles_1);
        Drawable profilesHide = AppCompatResources.getDrawable(context, R.drawable.ic_profiles_2);

        setAdapter();

        binding.addButton.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(context, R.style.Theme_Material3_DayNight_Dialog_Alert));
            EditText editText = new EditText(context);
            builder.setMessage(R.string.dialog_alert_add_profile)
                    .setPositiveButton("ok", (dialog, which) -> {
                        new KeymapProfiles(context).saveProfile(editText.getText().toString(), new ArrayList<>(), context.getPackageName());
                        setAdapter();
                    })
                    .setNegativeButton("cancel", (dialog, which) -> {})
                    .setView(editText);
            AlertDialog dialog = builder.create();
            dialog.show();
        });

        binding.profilesButton.setOnClickListener(v -> {
            switch (binding.profiles.getVisibility()) {
                case View.VISIBLE:{
                    binding.header.setVisibility(View.GONE);
                    binding.currentProfile.setVisibility(View.GONE);
                    binding.profiles.setVisibility(View.GONE);
                    binding.addButton.setVisibility(View.GONE);
                    binding.profilesButton.setForeground(profilesShow);
                    break;
                }
                case View.GONE:
                case View.INVISIBLE: {
                    binding.header.setVisibility(View.VISIBLE);
                    binding.currentProfile.setVisibility(View.VISIBLE);
                    binding.profiles.setVisibility(View.VISIBLE);
                    binding.addButton.setVisibility(View.VISIBLE);
                    binding.profilesButton.setForeground(profilesHide);
                    break;
                }
            }
        });
    }

    private void setAdapter() {
        binding.profiles.setAdapter(new ProfilesViewAdapter(getContext(), this::setAdapter));
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
