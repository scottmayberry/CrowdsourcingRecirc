package com.example.smayber8.helloworldsync;

import android.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.view.View;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import static com.example.smayber8.helloworldsync.R.id.map;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback {
//////////////////////////////////////////////////////////////////////////////
    //google maps stuff
    private GoogleMap mMap;
    private ArrayList<Marker> pol;
    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;
    private LocationRequest mLocationRequest;
    private DatabaseReference ref;
    private int radius;//meters
    private LatLngInt[][] posLis;
    private LatLngInt[][] pre;
    private ArrayList<Circle> currentCir;
    private ArrayList<Circle> previousCir;
    private ChildEventListener pollutionMap;
    private ValueEventListener updateRadius;
    Timer timer;
///////////////////////////////////////////////////////////////////////


    //Main Activity Stuff
    private static Context mContext;
    public static SdlService sdl;
/////////////////////////////////////////////////////////////////////////

    //LocationScreen Stuff
    AirRecircTriggered loc;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        mContext = getApplicationContext();
        startSyncProxyService();
        radius = 160;
        timer = new Timer();
        pollutionMap = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {

                LatLng latLng = new LatLng(getDoubleFromDatabase(dataSnapshot.child("latitude").getValue()),getDoubleFromDatabase(dataSnapshot.child("longitude").getValue()));
                int color = Color.RED;
                if(dataSnapshot.child("from").getValue() != null)
                {
                    if(!dataSnapshot.child("from").getValue().equals("manual"))
                        color = Color.GREEN;
                }
                currentCir.add(mMap.addCircle(new CircleOptions()
                        .center(latLng)
                        .radius(radius)
                        .strokeColor(Color.GRAY)
                        .fillColor(color)));

            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {

            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {


            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {

            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        };
        updateRadius = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if(dataSnapshot != null)
                    radius = (int)(getDoubleFromDatabase(dataSnapshot.getValue())*1609.34);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        };
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(map);
        mapFragment.getMapAsync(this);
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    LatLng ll = new LatLng(location.getLatitude(), location.getLongitude());
                    loc = new AirRecircTriggered(location.getLongitude(),location.getLatitude(), "manual");
                    sdl.setLocation(loc);
                    if (mMap != null) {
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 15));
                        if(posLis != null)//add specific listeners
                        {
                            int iLat = (int)(ll.latitude*100);
                            int iLong = (int)(ll.longitude*100);
                            if(!(posLis[1][1].getLatitude() == iLat && posLis[1][1].getLongitude() == iLong))
                            {
                                previousCir.clear();
                                for(int i = 0; i < currentCir.size();i++)
                                    previousCir.add(currentCir.get(i));
                                currentCir.clear();

                                pre = posLis.clone();
                                updatePosLis(ll);
                                removeListeners();
                                timer.schedule(new TimerTask() {
                                    @Override
                                    public void run() {
                                        removePreviousCircles();
                                    }
                                }, 2000L);
                                addListeners();
                            }
                        }
                        else
                        {
                            currentCir = new ArrayList<>();
                            previousCir = new ArrayList<>();
                            posLis = new LatLngInt[3][3];
                            updatePosLis(ll);
                            addListeners();
                            pre = posLis.clone();
                        }
                    }//if the map isnt null
                }
            }
        };
        pol = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
        }
        ref = FirebaseDatabase.getInstance().getReference();
        ref.child("radius").addValueEventListener(updateRadius);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }





////////////////////MAIN ACTIVITY///////////////////////////////
    /////////////////////////////////////////

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


// //////////////////!!!!!!!!!!!MAIN ACTIVITY!!!!!!!!!!!!///////////////////////////////
    /////////////////////////////////////////




///////////////////////////////MAPS////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////
    /////////////////////////////////////////////////////////////////////////
    private void removeListeners()
    {
        for(int i = 0; i < pre.length;i++)
            for(int g = 0; g < pre[i].length;g++)
            {
                ref.child("Position").child("" + pre[i][g].getLongitude()).child("" + pre[i][g].getLatitude()).removeEventListener(pollutionMap);
            }
    }
    private void addListeners()
    {
        for(int i = 0; i < posLis.length;i++)
            for(int g = 0; g < posLis[i].length;g++)
            {
                ref.child("Position").child("" + posLis[i][g].getLongitude()).child("" + posLis[i][g].getLatitude()).addChildEventListener(pollutionMap);
            }
    }
    private void removePreviousCircles()
    {
        for(int i = 0; i < previousCir.size();i++)
            previousCir.get(i).remove();
    }



    private void updatePosLis(LatLng ll)
    {
        for(int i = 0; i < posLis.length;i++) {
            double lat = ll.latitude + (i-1);
            if (lat < -180)
                lat += 360;
            if (lat > 180)
                lat -= 360;
            int iLat = (int) (lat * 100);
            for (int g = 0; g < posLis[i].length; g++) {
                double lon = ll.longitude + (g - 1);
                if (lon < -90)
                    lon = -90;
                if (lon > 90)
                    lon = 90;
                int iLon = (int) (lon * 100);
                posLis[i][g] = new LatLngInt(iLat, iLon);
            }
        }
    }


    private void startLocationUpdates() {

        try{
            mFusedLocationClient.requestLocationUpdates(mLocationRequest,
                    mLocationCallback,
                    null /* Looper */);}
        catch (SecurityException e ) {System.out.println("Security Exception");}

    }
    public double getDoubleFromDatabase(Object o)
    {
        if(o.getClass().getName().toLowerCase().equals("java.lang.long")) {
            return (new Long((Long) o).doubleValue());
        }else if(o.getClass().getName().toLowerCase().equals("java.lang.double")){
            return (double)(o);
        }
        else
            return 0;
    }
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        // TODO: Consider calling
                        //    ActivityCompat#requestPermissions
                        // here to request the missing permissions, and then overriding
                        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                        //                                          int[] grantResults)
                        // to handle the case where the user grants the permission. See the documentation
                        // for ActivityCompat#requestPermissions for more details.
                        return;
                    }
                    mMap.setMyLocationEnabled(true);
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
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setAllGesturesEnabled(false);
        // Add a marker in Sydney and move the camera
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        mMap.setMyLocationEnabled(true);
        LatLng sydney = new LatLng(-34, 151);
        Marker marker = mMap.addMarker(new MarkerOptions().position(sydney).title("Marker in Sydney"));
        //mMap.moveCamera(CameraUpdateFactory.newLatLng(sydney));
        //centerMapOnMyLocation();
        //marker.remove();
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(5000);
        mLocationRequest.setFastestInterval(2000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        startLocationUpdates();

    }
    ///////////////////////////////!!!!!!!!!!!!!!!!MAPS!!!!!!!!!!!!!!!!!!!////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////
    /////////////////////////////////////////////////////////////////////////

}
