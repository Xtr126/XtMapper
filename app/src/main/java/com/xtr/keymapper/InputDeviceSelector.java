package com.xtr.keymapper;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class InputDeviceSelector extends AppCompatActivity implements AdapterView.OnItemSelectedListener {

    List<String> devices;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_configure);
        Spinner spinner = findViewById(R.id.spinner);

        // Spinner click listener
        spinner.setOnItemSelectedListener(this);

        // Spinner Drop down elements
        devices = new ArrayList<>();
        // Creating adapter for spinner
        new Thread(this::getDevices).start();
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);

        // Drop down layout style - list view with radio button
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // attaching data adapter to spinner
        spinner.setAdapter(dataAdapter);
    }

    public void getDevices(){
        try {
            BufferedReader getevent = Utils.geteventStream(this);
            String line;
            while ((line = getevent.readLine()) != null) { //read events
                String[] xy = line.split(":"); //split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
                if (xy[1].equals("EV_REL"))
                    devices.add(xy[0]);
                
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // On selecting a spinner item
        String item = parent.getItemAtPosition(position).toString();

        // Showing selected spinner item
        Toast.makeText(parent.getContext(), "Selected: " + item, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // TODO Auto-generated method stub
    }


}