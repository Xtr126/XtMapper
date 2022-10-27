package com.xtr.keymapper.fragment;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.slider.Slider;
import com.xtr.keymapper.KeymapConfig;
import com.xtr.keymapper.R;
import com.xtr.keymapper.dpad.DpadConfig;

public class SettingsFragment extends BottomSheetDialogFragment {
    private final DpadConfig dpadConfig;
    private final KeymapConfig keymapConfig;
    private EditText inputDevice;

    public SettingsFragment(Context context) {
        dpadConfig = new DpadConfig(context);
        keymapConfig = new KeymapConfig(context);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        return inflater.inflate(R.layout.fragment_settings_dialog, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        BottomSheetBehavior.from(view).setState(BottomSheetBehavior.STATE_EXPANDED);

        final Slider sliderDpad = view.findViewById(R.id.slider_dpad);
        final Slider sliderMouse = view.findViewById(R.id.slider_mouse);

        sliderDpad.setValue(dpadConfig.getDpadRadiusMultiplier());
        sliderMouse.setValue(keymapConfig.getMouseSensitivity());

        sliderDpad.addOnChangeListener((slider, value, fromUser) -> dpadConfig.setDpadRadiusMultiplier(value));
        sliderMouse.addOnChangeListener((slider, value, fromUser) -> keymapConfig.setMouseSensitivity(value));

        inputDevice = view.findViewById(R.id.input_device);
        inputDevice.setText(keymapConfig.getDevice());
    }

    @Override
    public void onDestroyView() {
        String[] device = inputDevice.getText().toString().split("\\s+"); // split the string to allow only one string without whitespaces
        keymapConfig.setDevice(device[0]);
        super.onDestroyView();
    }

}