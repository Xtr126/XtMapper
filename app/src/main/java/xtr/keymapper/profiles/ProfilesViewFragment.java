package xtr.keymapper.profiles;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import xtr.keymapper.activity.MainActivity;
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
        setAdapter();

        if (getActivity() instanceof MainActivity)
            ((MainActivity) getActivity()).binding.addButton.setOnClickListener(v -> ProfileSelector.createNewProfile(context, p -> setAdapter()));
    }

    private void setAdapter() {
        if (binding != null) {
            profilesViewAdapter = new ProfilesViewAdapter(getContext(), this::setAdapter,
                    profileName -> {
                        if (getActivity() instanceof MainActivity)
                            ((MainActivity) getActivity()).onProfileSelected(profileName);
                    });
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
