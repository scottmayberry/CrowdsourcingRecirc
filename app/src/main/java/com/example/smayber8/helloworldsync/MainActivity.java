package com.example.smayber8.helloworldsync;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;


import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;

import static com.smartdevicelink.trace.enums.Mod.proxy;
//import static com.smartdevicelink.*;


public class MainActivity extends AppCompatActivity {

    private static Context mContext;
    public static SdlService sdl;
    SdlReceiver receiver;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = getApplicationContext();
        startSyncProxyService();
    }
    public void goToLockScreenActivity(View v)
    {
        Intent intent = new Intent(this, LockScreenActivity.class);
        startActivity(intent);
    }
    public static Context getmContext()
    {
        return mContext;
    }
    public static void setmContext(Context t)
    {
        mContext = t;
    }
    private void startSyncProxyService()
    {
        sdl = new SdlService();
        sdl.onCreate();
        sdl.startProxy();
    }//start sync proxy service

}
