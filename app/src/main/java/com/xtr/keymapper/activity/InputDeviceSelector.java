package com.xtr.keymapper.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.xtr.keymapper.KeymapConfig;
import com.xtr.keymapper.R;
import com.xtr.keymapper.Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class InputDeviceSelector extends AppCompatActivity implements AdapterView.OnItemSelectedListener {

    private Spinner spinner;
    // Spinner Drop down elements
    private final List<String> devices = new ArrayList<>();

    private ArrayAdapter<String> dataAdapter;

    private TextView textView;
    private TextView textView2;
    private Socket evSocket;
    private BufferedReader getevent;
    private PrintWriter pOut;

    private KeymapConfig keymapConfig;

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

        keymapConfig = new KeymapConfig(this);

        new Thread(this::getDevices).start();

        // Drop down layout style - list view with radio button
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        findViewById(R.id.button).setOnClickListener(v -> this.finish());
    }

    private void updateView(String s){
        runOnUiThread(() -> textView.append(s + "\n"));
    }

    @Override
    protected void onDestroy() {
        stop();
        super.onDestroy();
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // On selecting a spinner item
        String item = parent.getItemAtPosition(position).toString();
        textView2.setText(item);
        Server.changeDevice(item).start();
        keymapConfig.setDevice(item);
        // Showing selected spinner item
        Toast.makeText(parent.getContext(), item, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // TODO Auto-generated method stub
    }

    private void stop(){
        try {
            pOut.close();
            getevent.close();
            evSocket.close();
        } catch (IOException e) {
            Log.e("InputDeviceSelector", e.toString());
        }
    }

    private void getDevices()  {
        try {
            evSocket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT_2);
            pOut = new PrintWriter(evSocket.getOutputStream());
            pOut.println("getevent"); pOut.flush();
            getevent = new BufferedReader(new InputStreamReader(evSocket.getInputStream()));
            String input_event;
            String[] event;
            while ((input_event = getevent.readLine()) != null) {
                event = input_event.split("\\s+"); // split a string like "/dev/input/event2 EV_REL REL_X ffffffff"

                if(!event[2].equals("SYN_REPORT"))
                    updateView(input_event);

                if(!devices.contains(event[0]) && !textView2.getText().equals(event[0]))
                    if (event[1].equals("EV_REL")) {
                        devices.add(event[0]);
                        dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);
                        runOnUiThread(() -> spinner.setAdapter(dataAdapter));
                    }
            }
        } catch (IOException e) {
            Log.e("InputDeviceSelector", e.toString());
        }
    }
}