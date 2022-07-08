package com.xtr.keymapper;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class InputDeviceSelector extends AppCompatActivity implements AdapterView.OnItemSelectedListener {

    private List<String> devices;
    private Spinner spinner;
    private ArrayAdapter<String> dataAdapter;
    private TextView textView;
    int i = 0;
    private SharedPreferences sharedPref;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_configure);
        spinner = findViewById(R.id.spinner);
        textView = findViewById(R.id.textView);

        findViewById(R.id.button).setOnClickListener(v -> this.finish());

        // Load stored device name
        sharedPref = getSharedPreferences("devices", MODE_PRIVATE);
        String devname = sharedPref.getString("device", null);

        // Spinner click listener
        spinner.setOnItemSelectedListener(this);

        // Spinner Drop down elements
        devices = new ArrayList<>();
        if(devname != null)
        devices.add(devname);
        // Creating adapter for spinner

        new Thread(this::getDevices).start();
        dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);

        // Drop down layout style - list view with radio button
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // attaching data adapter to spinner
        spinner.setAdapter(dataAdapter);
    }

    private void updateView(String s){
        i++;
        if(i == 8) {
            i = 0;
            runOnUiThread(() -> textView.setText(s));
        } else {
            runOnUiThread(() -> textView.append("\n" + s));
        }
    }

    private void getDevices(){
        try {
            BufferedReader getevent = Utils.geteventStream(this);
            String stdout;
            while ((stdout = getevent.readLine()) != null) { //read events
                String[] xy = stdout.split("\\s+");
                //split a string like "/dev/input/event2 EV_REL REL_X ffffffff"
                if(!xy[2].equals("SYN_REPORT"))
                updateView(stdout);

                if(!devices.contains(xy[0]))
                    if (xy[1].equals("EV_REL")) {
                        devices.add(xy[0]);
                        dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);
                        runOnUiThread(() -> spinner.setAdapter(dataAdapter));
                   }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // On selecting a spinner item
        String item = parent.getItemAtPosition(position).toString();
        // Store selected device
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString("device", item);
        editor.apply();
        // Showing selected spinner item
        Toast.makeText(parent.getContext(), item, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // TODO Auto-generated method stub
    }


}