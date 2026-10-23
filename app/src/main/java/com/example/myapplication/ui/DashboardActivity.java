package com.example.myapplication.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import com.example.myapplication.R;

public class DashboardActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        Button btnStartTrip = findViewById(R.id.btn_start_trip);
        btnStartTrip.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, DriverMonitorActivity.class);
            startActivity(intent);
        });
    }
}
