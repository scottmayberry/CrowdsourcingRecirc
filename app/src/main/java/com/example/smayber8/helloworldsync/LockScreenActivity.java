package com.example.smayber8.helloworldsync;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

public class LockScreenActivity extends AppCompatActivity {

    private int temp = 16;
    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;
    private LocationRequest mLocationRequest;
    private Handler locHandler = new Handler();
    private AirRecircTriggered loc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock_screen);
        MainActivity.setmContext(getApplicationContext());
        SeekBar seekBar = (SeekBar)findViewById(R.id.seekBar);
        final TextView seekBarValue = (TextView)findViewById(R.id.seekBarValue);
        seekBarValue.setText("" + temp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress,
                                          boolean fromUser) {
                // TODO Auto-generated method stub
                temp = (int)(((double)progress)/100.0 * 13.0 + 16);
                seekBarValue.setText(String.valueOf(temp));

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // TODO Auto-generated method stub
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // TODO Auto-generated method stub
            }
        });
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createLocationRequest();
        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    System.out.println("Longitude " + location.getLongitude() + " Latitude " + location.getLatitude());
                    loc = new AirRecircTriggered(location.getLongitude(),location.getLatitude(), "manual");
                    MainActivity.sdl.setLocation(loc);
                }
            };
        };
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
        else
        {
            attemptToStartLocationServices();
        }
        beginLocationServicesUIUpdate();
    }
    @Override
    protected void onResume()
    {
        super.onResume();

    }
    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(5000);
        mLocationRequest.setFastestInterval(2000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }
    public void attemptToStartLocationServices()
    {
        try{
            mFusedLocationClient.requestLocationUpdates(mLocationRequest,
                    mLocationCallback,
                    null /* Looper */);}
        catch (SecurityException e ) {System.out.println("Security Exception");}
    }
    public void beginLocationServicesUIUpdate()
    {
        final Runnable checkLoc = new Runnable() {
            @Override
            public void run() {
                while(true)
                {
                    try
                    {
                        Thread.sleep(5000);
                        System.out.println("Thread Sleep");
                    } catch (InterruptedException e) { System.out.println("InterruptedExeption");}
                    pushLocation();
                }
            }
        };
        new Thread(checkLoc).start();
    }
    public void pushLocation()
    {
        locHandler.post(new Runnable() {
            @Override
            public void run()
            {
                System.out.println("LNG: " + loc.longitude);
                System.out.println("Lat: " + loc.latitude);
                if(loc != null) {
                    ((TextView) findViewById(R.id.longitudeText)).setText("" + loc.longitude);
                    ((TextView) findViewById(R.id.latitudeText)).setText("" + loc.latitude);
                    if(MainActivity.sdl.isInsideRange())
                        ((TextView) findViewById(R.id.insideRangeText)).setText("YES");
                    else
                        ((TextView) findViewById(R.id.insideRangeText)).setText("NO");
                }
                else
                {
                    ((TextView) findViewById(R.id.longitudeText)).setText("null");
                    ((TextView) findViewById(R.id.latitudeText)).setText("null");
                }
            }

        });
    }
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    attemptToStartLocationServices();
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.

                } else {

                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return;
            }

            // other 'case' lines to check for other
            // permissions this app might request
        }
    }
    @Override
    public void onBackPressed() {
    }

    public void toggleRecirc(View v)
    {
        MainActivity.sdl.toggleRecirc();
    }

    public void changeTemp(View v)
    {
        MainActivity.sdl.UpdateTemp(temp);
    }

}
