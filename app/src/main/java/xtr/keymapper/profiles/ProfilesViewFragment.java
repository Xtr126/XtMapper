package xtr.keymapper.profiles;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.Fragment;

import xtr.keymapper.R;
import xtr.keymapper.databinding.FragmentProfilesViewBinding;

public class ProfilesViewFragment extends Fragment {
    private FragmentProfilesViewBinding binding;
    private ProfilesViewAdapter profilesViewAdapter;

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

        binding.addButton.setOnClickListener(v -> ProfileSelector.createNewProfile(context, p -> setAdapter()));

        binding.profilesButton.setOnClickListener(v -> {
            switch (binding.profiles.getVisibility()) {
                case View.VISIBLE:{
                    binding.profiles.setVisibility(View.GONE);
                    binding.addButton.setVisibility(View.GONE);
                    binding.profilesButton.setForeground(profilesShow);
                    break;
                }
                case View.GONE:
                case View.INVISIBLE: {
                    binding.profiles.setVisibility(View.VISIBLE);
                    binding.addButton.setVisibility(View.VISIBLE);
                    binding.profilesButton.setForeground(profilesHide);
                    break;
                }
            }
        });
    }

    private void setAdapter() {
        if (binding != null) {
            profilesViewAdapter = new ProfilesViewAdapter(getContext(), this::setAdapter);
            binding.profiles.setAdapter(profilesViewAdapter);
        }
    }

    @Override
    public void onDestroyView() {
        profilesViewAdapter = null;
        binding = null;
        super.onDestroyView();
    }
}
