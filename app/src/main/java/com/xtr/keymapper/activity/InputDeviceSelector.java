package com.xtr.keymapper.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.xtr.keymapper.R;
import com.xtr.keymapper.Utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class InputDeviceSelector extends AppCompatActivity implements AdapterView.OnItemSelectedListener {

    private Spinner spinner;
    // Spinner Drop down elements
    private final List<String> devices = new ArrayList<>();

    private ArrayAdapter<String> dataAdapter;

    private TextView textView;
    private TextView textView2;

    private SharedPreferences sharedPref;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_configure);
        spinner = findViewById(R.id.spinner);
        textView = findViewById(R.id.textView);
        textView2 = findViewById(R.id.textView2);

        // Spinner click listener
        spinner.setOnItemSelectedListener(this);

        // Creating adapter for spinner
        dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);

        // attaching data adapter to spinner
        spinner.setAdapter(dataAdapter);

        sharedPref = getSharedPreferences("settings", MODE_PRIVATE);
        String device = sharedPref.getString("device", null);
        if (device != null) {
            devices.add(device);
            textView2.setText(device);
        }

        new Thread(this::getDevices).start();

        // Drop down layout style - list view with radio button
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        findViewById(R.id.button).setOnClickListener(v -> this.finish());
    }

    private void updateView(String s){
        runOnUiThread(() -> textView.append(s + "\n"));
    }

    private void getDevices(){
        try {
            BufferedReader getevent = Utils.geteventStream(this);
            String stdout;
            while ((stdout = getevent.readLine()) != null) { //read events
                String[] xy = stdout.split("\\s+");
                //split a string like "/dev/input/event2 EV_REL REL_X ffffffff"
                if(!xy[2].equals("SYN_REPORT")) {
                    updateView(stdout);
                }
                if(xy[0].equals("root")) runOnUiThread(() -> textView.setTextSize(20));
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
        textView2.setText(item);

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