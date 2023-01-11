package xtr.keymapper.activity;

import android.os.Bundle;
import android.os.RemoteException;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.KeymapConfig;
import xtr.keymapper.databinding.ActivityConfigureBinding;
import xtr.keymapper.server.InputService;

public class InputDeviceSelector extends AppCompatActivity implements AdapterView.OnItemSelectedListener {

    // Spinner Drop down elements
    private final List<String> devices = new ArrayList<>();

    private ArrayAdapter<String> dataAdapter;
    private ActivityConfigureBinding binding;

    private KeymapConfig keymapConfig;
    private IRemoteService mService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityConfigureBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);

        // Spinner click listener
        binding.spinner.setOnItemSelectedListener(this);

        // Creating adapter for spinner
        dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);

        // attaching data adapter to spinner
        binding.spinner.setAdapter(dataAdapter);

        keymapConfig = new KeymapConfig(this);


        // Drop down layout style - list view with radio button
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mService = InputService.getInstance();
        binding.endButton.setOnClickListener(v -> this.finish());
    }

    private void updateView(String s){
        runOnUiThread(() -> binding.textView.append(s + "\n"));
    }

    @Override
    protected void onDestroy() {
        try {
            stop();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // On selecting a spinner item
        String item = parent.getItemAtPosition(position).toString();
        binding.textView2.setText(item);
        keymapConfig.setDevice(item);
        // Showing selected spinner item
        Toast.makeText(parent.getContext(), item, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // TODO Auto-generated method stub
    }

    private void stop() throws RemoteException {
        mService.unregisterCallback(mCallback);
    }

    private final IRemoteServiceCallback mCallback = new IRemoteServiceCallback.Stub() {
        @Override
        public void onMouseEvent(int code, int value) {
        }

        @Override
        public void receiveEvent(String event) {
            getDevices(event);
        }
    };


    private void getDevices(String event)  {
        String[] input_event, data;
        String evdev;
        data = event.split(":"); // split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
        evdev = data[0];
        input_event = data[1].split("\\s+");
        if(!input_event[1].equals("EV_SYN"))
            updateView(event);

        if( !devices.contains(evdev) && ! binding.textView2.getText().equals(evdev) )
            if (input_event[1].equals("EV_REL")) {
                devices.add(evdev);
                dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);
                runOnUiThread(() -> binding.spinner.setAdapter(dataAdapter));
            }
    }
}