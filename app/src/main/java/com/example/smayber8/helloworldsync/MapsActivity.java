package com.example.smayber8.helloworldsync;

import android.*;
import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import static com.example.smayber8.helloworldsync.R.id.map;

public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback {
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
    boolean switchPosition = false;
    boolean clearPreviousCircles = false;

/////////////////////////////////////////////////////////////////////////

    //Bluetooth stuff
    private final static String TAG = MapsActivity.class.getSimpleName();


    private ArrayList<BluetoothDevice> mBluetoothDevices;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice currentDevice;
    private boolean mScanning;
    private Handler mHandler;

    private int mData;

    public TextView mDataField;

    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;

    private int threshold = 1300;

    private final Long SCAN_PERIOD = 10000L;
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(currentDevice.getAddress());
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                getGattServices(mBluetoothLeService.getSupportedGattServices());
                for(int i = 0; i < mGattCharacteristics.size();i++)
                    for(int g = 0; g < mGattCharacteristics.get(i).size();g++)
                        mBluetoothLeService.setCharacteristicNotification(mGattCharacteristics.get(i).get(g), true);

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                setMData(intent.getIntExtra(BluetoothLeService.EXTRA_DATA, 0));
            }
        }
    };
    public synchronized void setMData(int mData)
    {
        this.mData = mData;
        sdl.updateAirQualityText(mData);
        if(this.mData > threshold)
            sdl.turnOnAirRecircBecauseAirQuality();
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        mContext = getApplicationContext();
        mScanning = false;
        mHandler = new Handler();
        mBluetoothDevices = new ArrayList<>();


        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION)) {

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        1);
            }
        }
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    android.Manifest.permission.BLUETOOTH)) {

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.BLUETOOTH},
                        2);
            }
        }
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    android.Manifest.permission.BLUETOOTH_ADMIN)) {

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_ADMIN},
                        3);
            }
        }

        startSyncProxyService();
        radius = 160;
        timer = new Timer();
        pollutionMap = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {

                LatLng latLng = new LatLng(getDoubleFromDatabase(dataSnapshot.child("latitude").getValue()),getDoubleFromDatabase(dataSnapshot.child("longitude").getValue()));
                int color = Color.argb(50, 225, 0, 0);
                if(dataSnapshot.child("from").getValue() != null)
                {
                    if(!dataSnapshot.child("from").getValue().toString().toLowerCase().equals("manual"))
                        color = Color.argb(50, 0, 225, 0);
                }
                currentCir.add(mMap.addCircle(new CircleOptions()
                        .center(latLng)
                        .radius(radius)
                        .strokeColor(Color.GRAY)
                        .fillColor(color)));
                currentCir.get(currentCir.size()-1).setClickable(true);
                currentCir.get(currentCir.size()-1).setTag(dataSnapshot.getKey());
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {

            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                LatLng latLng = new LatLng(getDoubleFromDatabase(dataSnapshot.child("latitude").getValue()),getDoubleFromDatabase(dataSnapshot.child("longitude").getValue()));
                for(int i = 0; i < currentCir.size();i++)
                    if(currentCir.get(i).getCenter().longitude == latLng.longitude && currentCir.get(i).getCenter().latitude == latLng.latitude)
                    {
                        sdl.checkCircleRemove(latLng);
                        currentCir.remove(i).remove();
                        i--;
                    }

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
                if(dataSnapshot != null) {
                    radius = (int) (getDoubleFromDatabase(dataSnapshot.getValue()) * 1609.34);
                    for(int i = 0; i < currentCir.size();i++)
                        currentCir.get(i).setRadius(radius);
                    sdl.setRadius(radius);
                }
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
                    if(switchPosition)
                    {
                        location.setLongitude(location.getLongitude() + .01);
                    }
                    if(clearPreviousCircles) {
                        removePreviousCircles();
                        clearPreviousCircles = false;
                    }
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
                                previousCir = (ArrayList<Circle>)currentCir.clone();

                                currentCir.clear();

                                pre = posLis.clone();
                                updatePosLis(ll);
                                removeListeners();
                                addListeners();
                                timer.schedule(new TimerTask() {
                                    @Override
                                    public void run() {
                                        clearPreviousCircles = true;
                                    }
                                }, 1000L);

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
        sdl.setRadius(radius);
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
                LatLngInt a = posLis[i][g];
                ref.child("Position").child("" + posLis[i][g].getLongitude()).child("" + posLis[i][g].getLatitude()).addChildEventListener(pollutionMap);
            }
    }
    private void removePreviousCircles()
    {
        for(int i = 0; i < previousCir.size();i++) {
            previousCir.remove(i).remove();
            i--;
        }
    }



    private void updatePosLis(LatLng ll)
    {
        for(int i = 0; i < posLis.length;i++) {
            double lat = ll.latitude + ((double)(i - 1))/100.0;
            if (lat < -180)
                lat += 360;
            if (lat > 180)
                lat -= 360;
            int iLat = (int) (lat * 100);
            for (int g = 0; g < posLis[i].length; g++) {
                double lon = ll.longitude + ((double)(g - 1))/100.0;
                if (lon < -90)
                    lon = -90;
                if (lon > 90)
                    lon = 90;
                int iLon = (int) (lon * 100);
                posLis[i][g] = new LatLngInt(iLat, iLon);
            }
        }
    }
    private LatLng roundLatLng(LatLng ll)
    {
        int iLat = (int)(ll.latitude*100);
        int iLon = (int)(ll.longitude*100);
        return new LatLng(iLat, iLon);
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
        mMap.setOnMapClickListener(new GoogleMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng latLng1) {
                int iLat = (int)(latLng1.latitude*100);
                int iLon = (int)(latLng1.longitude*100);
                //String key = ref.child("Position").child("" + iLon).child("" + iLat).push().getKey();
                sdl.writeToDatabase(latLng1.longitude, latLng1.latitude, true, ref);
                //ref.child("Position").child("" + iLon).child("" + iLat).child(key).setValue(new AirRecircTriggered(latLng1.longitude, latLng1.latitude, "manual"));
            }
        });
        mMap.setOnCircleClickListener(new GoogleMap.OnCircleClickListener() {
            @Override
            public void onCircleClick(Circle circle) {
                for(int i = 0; i < currentCir.size();i++)
                    if(currentCir.get(i).getCenter().longitude == circle.getCenter().longitude && currentCir.get(i).getCenter().latitude == circle.getCenter().latitude)
                    {
                        sdl.removeFromDatabase(circle.getCenter().longitude, circle.getCenter().latitude, circle.getTag().toString(), ref);
                        //currentCir.remove(i).remove();
                        //i--;
                    }
            }
        });


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
        //Marker marker = mMap.addMarker(new MarkerOptions().position(sydney).title("Marker in Sydney"));
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


    /////////////////////////////Bluetooth/////////////////////////////////////////////
    /////////////////////////////////////////////////////////////////////////////////
    private void displayData(String data) {
        if (data != null) {
            mDataField.setText(data);
        }
    }
    private void getGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                charas.add(gattCharacteristic);
            }
            mGattCharacteristics.add(charas);
        }
        return;
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.bluetooth_menu, menu);
        if(!mConnected) {
            for(int i = 0; i < mBluetoothDevices.size();i++)
            {
                String identifier;
                String str = mBluetoothDevices.get(i).getBluetoothClass().toString();
                if(mBluetoothDevices.get(i).getName() == null)
                    identifier = "No name";
                else
                    identifier = mBluetoothDevices.get(i).getName();
                menu.add(Menu.NONE, i, Menu.NONE, identifier);
            }
            menu.findItem(R.id.menu_disconnect).setVisible(false);
            if (!mScanning) {
                menu.findItem(R.id.menu_stop).setVisible(false);
                menu.findItem(R.id.menu_scan).setVisible(true);
                menu.findItem(R.id.menu_refresh).setActionView(null);
            } else {
                menu.findItem(R.id.menu_stop).setVisible(true);
                menu.findItem(R.id.menu_scan).setVisible(false);
                menu.findItem(R.id.menu_refresh).setActionView(
                        R.layout.actionbar_indeterminate_progress);
            }
        }
        else
        {
            menu.findItem(R.id.menu_stop).setVisible(false);
            menu.findItem(R.id.menu_scan).setVisible(false);
            menu.findItem(R.id.menu_refresh).setActionView(null);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled()) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 1);
            }
        }

        // Initializes list view adapter.
        mBluetoothDevices = new ArrayList<>();
        scanLeDevice(true);
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == 1 && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch(item.getItemId())
        {
            case R.id.menu_scan:

                /*switchPosition = !switchPosition;
                Toast.makeText(this, "" + switchPosition, Toast.LENGTH_SHORT).show();*/

                mBluetoothDevices.clear();
                scanLeDevice(true);
                break;
            case R.id.menu_stop:
                scanLeDevice(false);
                break;
            case android.R.id.home:
                onBackPressed();
                break;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                mConnected = false;
                invalidateOptionsMenu();
                break;
            default:
                scanLeDevice(false);
                currentDevice = mBluetoothDevices.get(item.getItemId());
                Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
                getBaseContext().getApplicationContext().bindService(gattServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
                this.registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
                if (mBluetoothLeService != null) {
                    final boolean result = mBluetoothLeService.connect(currentDevice.getAddress());
                    Log.d(TAG, "Connect request result=" + result);
                }


        }

        return true;
    }
    private void scanLeDevice(final boolean enable) {
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    invalidateOptionsMenu();
                }
            }, SCAN_PERIOD);

            mScanning = true;
            mBluetoothAdapter.startLeScan(mLeScanCallback);
        } else {
            mScanning = false;
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
        invalidateOptionsMenu();
    }
    @Override
    protected void onPause() {
        super.onPause();
        scanLeDevice(false);
        mBluetoothDevices.clear();
    }
    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {

                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if(!mBluetoothDevices.contains(device)) {
                                mBluetoothDevices.add(device);
                                invalidateOptionsMenu();
                            }
                        }
                    });
                }
            };
    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }


}
