package com.example.smayber8.helloworldsync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.exception.SdlExceptionCause;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCRequest;
import com.smartdevicelink.proxy.SdlProxyALM;
import com.smartdevicelink.proxy.callbacks.OnServiceEnded;
import com.smartdevicelink.proxy.callbacks.OnServiceNACKed;
import com.smartdevicelink.proxy.interfaces.IProxyListenerALM;
import com.smartdevicelink.proxy.rc.datatypes.ClimateControlData;
import com.smartdevicelink.proxy.rc.datatypes.InteriorZone;
import com.smartdevicelink.proxy.rc.datatypes.ModuleData;
import com.smartdevicelink.proxy.rc.datatypes.ModuleDescription;
import com.smartdevicelink.proxy.rc.enums.ModuleType;
import com.smartdevicelink.proxy.rc.rpc.*;
import com.smartdevicelink.proxy.rpc.*;
import com.smartdevicelink.proxy.rpc.enums.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class SdlService extends Service implements IProxyListenerALM{

    private static final String TAG 					= "SDL Service";

    private static final String APP_NAME 				= "Air Purification";
    private static final String APP_ID 					= "8675310";

    private static final String ICON_FILENAME 			= "sdl_128.png";
    private int iconCorrelationId;

    List<String> remoteFiles;

    private String uniqueID;

    private static final String WELCOME_SHOW 			= "Welcome to Air Purification";
    private static final String WELCOME_SPEAK 			= "Welcome to Aid Purification";

    private static final String TEST_COMMAND_NAME 		= "Crowdsourcing for Your Health";
    private static final int TEST_COMMAND_ID 			= 1;

    // variable used to increment correlation ID for every request sent to SYNC
    public int autoIncCorrId = 0;

    // variable to contain the current state of the service
    private static SdlService instance = null;

    // variable to create and call functions of the SyncProxy
    private SdlProxyALM proxy = null;

    private boolean lockscreenDisplayed = false;

    private boolean firstNonHmiNone = true;
    private boolean isVehicleDataSubscribed = false;

    private int savedTemp;
    private int savedFanSpeed;

    private InteriorZone zone = new InteriorZone();
    private int currentTemp;
    private int currentFanSpeed;
    private boolean recirc;
    private boolean recircStateChanged = false;
    private Long lon = 5000L;
    private Timer gpsTimer;
    private double radius = 1.0;
    public static AirRecircTriggered location;
    private AirRecircTriggered insideRadius;
    private boolean autoToggleAir = false;
    private boolean manual = true;

    private class checkAirArea extends TimerTask {
        @Override
        public void run()
        {
            System.out.println("Check Timer");
            ValueEventListener postListener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    // Get Post object and use the values to update the UI
                    System.out.println("onDataChange");
                    if(dataSnapshot != null)
                        for (DataSnapshot position: dataSnapshot.getChildren()) {

                            AirRecircTriggered temp = new AirRecircTriggered( getDoubleFromDatabase(position.child("longitude").getValue()),getDoubleFromDatabase(position.child("latitude").getValue()),
                                    position.child("from").getValue().toString());
                            if(!recirc && locationWithinRange(location,temp, radius, false)) {
                                insideRadius = temp;
                                autoToggleAir = true;
                                try {
                                    proxy.speak("Air Recirculation Turned On", autoIncCorrId++);
                                }catch (SdlException e) { System.out.println("Air recirculation voice command failed");}
                                turnOnAirRecirc();
                            }
                        }//search through specific data structure
                }//onDataChange

                @Override
                public void onCancelled(DatabaseError databaseError) {
                    // Getting Post failed, log a message
                    Log.w(TAG, "loadPost:onCancelled", databaseError.toException());
                    // ...
                }//onCancelled
            };
            if(location != null) {
                if(insideRadius == null) {//only check if moved outside current radius or there has not been a radius yet
                    int lng = (int) (location.longitude * 100);
                    int latitu = (int) (location.latitude * 100);
                    Util.ref.child("Position").child("" + lng).child("" + latitu).addListenerForSingleValueEvent(postListener);
                    if(!Util.global)
                        Util.ref.child("Local").child(uniqueID).child("" + lng).child("" + latitu).addListenerForSingleValueEvent(postListener);
                    System.out.println("LNG: " + lng + " Lat: " + latitu);
                }
                else
                {//checking to see if still inside current air radius range
                    if(!locationWithinRange(location,insideRadius, radius, true))
                    {
                        turnOffAirRecirc();
                        insideRadius = null;
                    }
                }

            }
        }
    }
    public boolean isInsideRange()
    {
        if(insideRadius == null)
            return false;
        return true;
    }
    public void setLocation(AirRecircTriggered locat)
    {
        location = locat;
    }
    public void turnOffAirRecirc()
    {
        ClimateControlData cd = new ClimateControlData();
        cd.setRecirculateEnabled(false);

        cd.setInteriorDataType(ModuleType.CLIMATE);
        ModuleData mdata = new ModuleData();
        mdata.setModuleZone(zone);

        mdata.setModuleType(ModuleType.CLIMATE);
        mdata.setControlData(cd);

        SetInteriorVehicleData sd = new SetInteriorVehicleData();
        sd.setCorrelationID(autoIncCorrId++);
        sd.setModuleData(mdata);

        try {
            //   proxy.show("ID " + autoIncCorrId , null, null, autoIncCorrId++);
            proxy.sendRPCRequest(sd);
            //    mHandler.postDelayed(r, 3000);
        } catch (SdlException e) {
            e.printStackTrace();
        }
    }
    public void turnOnAirRecircBecauseAirQuality()
    {
        if(!recirc)
        {
            manual = false;
            turnOnAirRecirc();
        }

    }
    public void turnOnAirRecirc()
    {
        ClimateControlData cd = new ClimateControlData();
        cd.setRecirculateEnabled(true);

        cd.setInteriorDataType(ModuleType.CLIMATE);
        ModuleData mdata = new ModuleData();
        mdata.setModuleZone(zone);

        mdata.setModuleType(ModuleType.CLIMATE);
        mdata.setControlData(cd);

        SetInteriorVehicleData sd = new SetInteriorVehicleData();
        sd.setCorrelationID(autoIncCorrId++);
        sd.setModuleData(mdata);

        try {
            //   proxy.show("ID " + autoIncCorrId , null, null, autoIncCorrId++);
            proxy.sendRPCRequest(sd);
            //    mHandler.postDelayed(r, 3000);
        } catch (SdlException e) {
            e.printStackTrace();
        }
    }
    public void toggleRecirc()
    {

        if(recirc)
            turnOffAirRecirc();
        else {
            turnOnAirRecirc();
        }
    }
    public void refreshCabin()
    {
        savedTemp = currentTemp;
        savedFanSpeed = currentFanSpeed;
        //UpdateTemp(16);
        UpdateFanSpeed(70);
        Timer timer = new Timer(true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                //UpdateTemp(savedTemp);
                UpdateFanSpeed(savedFanSpeed);
            }
        },10000);

    }
    public boolean locationWithinRange(AirRecircTriggered pos1, AirRecircTriggered pos2, double r, boolean adjuster)
    {
        if(adjuster)//adjuster prevents rapid switching between on and off states
            r+= 0;
        System.out.println("Distance between points: " + Math.sqrt((pos1.longitude-pos2.longitude)*(pos1.longitude-pos2.longitude)*69*69 + (pos1.latitude-pos2.latitude)*(pos1.latitude-pos2.latitude)*69*69));
        double m = Math.sqrt((pos1.longitude-pos2.longitude)*(pos1.longitude-pos2.longitude)*69*69 + (pos1.latitude-pos2.latitude)*(pos1.latitude-pos2.latitude)*69*69);
        if(m <= r)
            return true;
        return false;
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

    public double[] getLocationData()
    {
        double d[] =  {location.longitude, location.latitude};
        return d;
    }//get location data


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        remoteFiles = new ArrayList<String>();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            startProxy();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        disposeSyncProxy();
        //LockScreenManager.clearLockScreen();
        instance = null;
        super.onDestroy();
    }
    public void setUniqueID(String str)
    {
        uniqueID = str;
    }

    public static SdlService getInstance() {
        return instance;
    }

    public SdlProxyALM getProxy() {
        return proxy;
    }

    public void startProxy() {
        if (proxy == null) {
            try {
                proxy = new SdlProxyALM(MapsActivity.getmContext(),this, APP_NAME,false, Language.EN_US, Language.EN_US ,APP_ID);
                zone.setColumn(0);
                zone.setRow(0);
                zone.setLevel(0);
                zone.setLevelSpan(0);
                zone.setColumnSpan(1);
                zone.setRowSpan(1);
            } catch (SdlException e) {
                e.printStackTrace();
                // error creating proxy, returned proxy = null
                if (proxy == null) {
                    stopSelf();
                }
            }
        }
    }

    public void disposeSyncProxy() {
        if (proxy != null) {
            try {
                proxy.dispose();
            } catch (SdlException e) {
                e.printStackTrace();
            }
            proxy = null;
            //LockScreenManager.clearLockScreen();
        }
        this.firstNonHmiNone = true;
        this.isVehicleDataSubscribed = false;

    }

    public void reset() {
        if (proxy != null) {
            try {
                proxy.resetProxy();
                this.firstNonHmiNone = true;
                this.isVehicleDataSubscribed = false;
            } catch (SdlException e1) {
                e1.printStackTrace();
                //something goes wrong, & the proxy returns as null, stop the service.
                // do not want a running service with a null proxy
                if (proxy == null) {
                    stopSelf();
                }
            }
        } else {
            startProxy();
        }
    }

    /**
     * Will show a sample test message on screen as well as speak a sample test message
     */
    public void showTest(){
        try {
            proxy.show(TEST_COMMAND_NAME, "Command has been selected", TextAlignment.CENTERED, autoIncCorrId++);
            proxy.speak(TEST_COMMAND_NAME, autoIncCorrId++);
        } catch (SdlException e) {
            e.printStackTrace();
        }
    }

    public void updateAirQualityText(int airQualityNumber)
    {
        try {
            proxy.show(TEST_COMMAND_NAME, "Air Quality: " + airQualityNumber, TextAlignment.CENTERED, autoIncCorrId++);
            //proxy.speak(TEST_COMMAND_NAME, autoIncCorrId++);
        } catch (SdlException e) {
            e.printStackTrace();
        }
    }

    /**
     *  Add commands for the app on SDL.
     */
    public void sendCommands(){
        System.out.println("SEND COMMANDS");
        AddCommand command = new AddCommand();
        MenuParams params = new MenuParams();
        params.setMenuName(TEST_COMMAND_NAME);
        command = new AddCommand();
        command.setCmdID(TEST_COMMAND_ID);
        command.setMenuParams(params);
        command.setVrCommands(Arrays.asList(new String[]{TEST_COMMAND_NAME}));
        sendRpcRequest(command);
    }

    /**
     * Sends an RPC Request to the connected head unit. Automatically adds a correlation id.
     * @param request
     */
    private void sendRpcRequest(RPCRequest request){
        request.setCorrelationID(autoIncCorrId++);
        try {
            proxy.sendRPCRequest(request);
        } catch (SdlException e) {
            e.printStackTrace();
        }
    }
    public void UpdateTemp(int temp){
        if (true) {

            Log.e("Climate Temp", "sendTemperatureRPC: " + temp);
            if (temp > 15 && temp < 30) {
                ClimateControlData cd = new ClimateControlData();
                cd.setDesiredTemp(temp);

                //cd.setRecirculateEnabled();

                cd.setInteriorDataType(ModuleType.CLIMATE);
                ModuleData mdata = new ModuleData();
                mdata.setModuleZone(zone);

                mdata.setModuleType(ModuleType.CLIMATE);
                mdata.setControlData(cd);

                SetInteriorVehicleData sd = new SetInteriorVehicleData();
                sd.setCorrelationID(autoIncCorrId++);
                sd.setModuleData(mdata);

                try {
                    currentTemp = temp;
                    //   proxy.show("ID " + autoIncCorrId , null, null, autoIncCorrId++);
                    proxy.sendRPCRequest(sd);
                    //    mHandler.postDelayed(r, 3000);
                } catch (SdlException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    public void UpdateFanSpeed(int temp) {
        if (true) {

            Log.e("Climate Temp", "sendTemperatureRPC: " + temp);
            if (temp > 15 && temp < 30) {
                ClimateControlData cd = new ClimateControlData();
                cd.setFanSpeed(temp);

                //cd.setRecirculateEnabled();

                cd.setInteriorDataType(ModuleType.CLIMATE);
                ModuleData mdata = new ModuleData();
                mdata.setModuleZone(zone);

                mdata.setModuleType(ModuleType.CLIMATE);
                mdata.setControlData(cd);

                SetInteriorVehicleData sd = new SetInteriorVehicleData();
                sd.setCorrelationID(autoIncCorrId++);
                sd.setModuleData(mdata);

                try {
                    currentFanSpeed = temp;
                    //   proxy.show("ID " + autoIncCorrId , null, null, autoIncCorrId++);
                    proxy.sendRPCRequest(sd);
                    //    mHandler.postDelayed(r, 3000);
                } catch (SdlException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Sends the app icon through the uploadImage method with correct params
     * @throws SdlException
     */
    private void sendIcon() throws SdlException {
        iconCorrelationId = autoIncCorrId++;
        uploadImage(R.drawable.ic_launcher, ICON_FILENAME, iconCorrelationId, true);
    }

    /**
     * This method will help upload an image to the head unit
     * @param resource the R.drawable.__ value of the image you wish to send
     * @param imageName the filename that will be used to reference this image
     * @param correlationId the correlation id to be used with this request. Helpful for monitoring putfileresponses
     * @param isPersistent tell the system if the file should stay or be cleared out after connection.
     */
    private void uploadImage(int resource, String imageName,int correlationId, boolean isPersistent){
        PutFile putFile = new PutFile();
        putFile.setFileType(FileType.GRAPHIC_PNG);
        putFile.setSdlFileName(imageName);
        putFile.setCorrelationID(correlationId);
        putFile.setPersistentFile(isPersistent);
        putFile.setSystemFile(false);
        putFile.setBulkData(contentsOfResource(resource));

        try {
            proxy.sendRPCRequest(putFile);
        } catch (SdlException e) {
            e.printStackTrace();
        }
    }

    /**
     * Helper method to take resource files and turn them into byte arrays
     * @param resource
     * @return
     */
    private byte[] contentsOfResource(int resource) {
        InputStream is = null;
        try {
            is = getResources().openRawResource(resource);
            ByteArrayOutputStream os = new ByteArrayOutputStream(is.available());
            final int buffersize = 4096;
            final byte[] buffer = new byte[buffersize];
            int available = 0;
            while ((available = is.read(buffer)) >= 0) {
                os.write(buffer, 0, available);
            }
            return os.toByteArray();
        } catch (IOException e) {
            Log.w("SDL Service", "Can't read icon file", e);
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onProxyClosed(String info, Exception e, SdlDisconnectedReason reason) {

        System.out.println("Calling OnProxyClosed");
        if(!(e instanceof SdlException)){
            Log.v(TAG, "reset proxy in onproxy closed");
            reset();
        }
        else if ((((SdlException) e).getSdlExceptionCause() != SdlExceptionCause.SDL_PROXY_CYCLED))
        {
            if (((SdlException) e).getSdlExceptionCause() != SdlExceptionCause.BLUETOOTH_DISABLED)
            {
                Log.v(TAG, "reset proxy in onproxy closed");
                reset();
            }
        }

        clearLockScreen();

        stopSelf();
    }

    @Override
    public void onOnHMIStatus(OnHMIStatus notification) {
        System.out.println("onOnHMIStatus: "+ notification.getHmiLevel());
        if(notification.getHmiLevel().equals(HMILevel.HMI_FULL)){
            if (notification.getFirstRun()) {
                // send welcome message if applicable
                performWelcomeMessage();
            }
            if(proxy != null)
                try {
                    if(!isVehicleDataSubscribed)
                    {
                        //isVehicleDataSubscribed = true
                        if(gpsTimer == null) {
                            //instantiate database and timer all at once
                            //mDatabase = FirebaseDatabase.getInstance().getReference();
                            //mDatabase.child("user").setValue(true);
                            //mDatabase.child("user").setValue(new AirRecircTriggered(42.857, -83.527));
                            //System.out.println("mDatabase: " + mDatabase);
                            gpsTimer = new Timer(true);
                            gpsTimer.scheduleAtFixedRate(new checkAirArea(), lon, lon);
                        }
                        proxy.subscribevehicledata(false, true, true, false, false, false, true, true, false, false, false, false, false, true, autoIncCorrId++);
                        System.out.println("Subscribed");
                        ModuleDescription description = new ModuleDescription();
                        description.setModuleType(ModuleType.CLIMATE);
                        description.setZone(zone);

                        isVehicleDataSubscribed=true;

                        GetInteriorVehicleData data = new GetInteriorVehicleData();
                        data.setModuleDescription(description);
                        data.setSubscribed(true);
                        data.setCorrelationID(autoIncCorrId++);
                        proxy.sendRPCRequest(data);

                        //Start gpsTimer

                        System.out.println("Success in subscription to vehicle data");
                    }

                }catch (SdlException e) {System.out.println("Error in subscribe vehicle data");}
            // Other HMI (Show, PerformInteraction, etc.) would go here
        }


        if(!notification.getHmiLevel().equals(HMILevel.HMI_NONE)
                && firstNonHmiNone){
            sendCommands();
            //uploadImages();
            firstNonHmiNone = false;
            //performWelcomeMessage();

            // Other app setup (SubMenu, CreateChoiceSet, etc.) would go here
        }else{
            //We have HMI_NONE
            if(notification.getFirstRun()){
                uploadImages();
            }
        }



    }

    /**
     * Will show a sample welcome message on screen as well as speak a sample welcome message
     */
    private void performWelcomeMessage(){
        try {
            //Set the welcome message on screen
            proxy.show(APP_NAME, WELCOME_SHOW, TextAlignment.CENTERED, autoIncCorrId++);

            //Say the welcome message
            proxy.speak(WELCOME_SPEAK, autoIncCorrId++);

        } catch (SdlException e) {
            e.printStackTrace();
        }

    }
    public void setRadius(double r)
    {
        radius = r*0.000621371;
    }

    /**
     *  Requests list of images to SDL, and uploads images that are missing.
     */
    private void uploadImages(){
        ListFiles listFiles = new ListFiles();
        this.sendRpcRequest(listFiles);

    }

    @Override
    public void onListFilesResponse(ListFilesResponse response) {
        Log.i(TAG, "onListFilesResponse from SDL ");
        if(response.getSuccess()){
            remoteFiles = response.getFilenames();
        }

        // Check the mutable set for the AppIcon
        // If not present, upload the image
        if(remoteFiles== null || !remoteFiles.contains(SdlService.ICON_FILENAME)){
            try {
                sendIcon();
            } catch (SdlException e) {
                e.printStackTrace();
            }
        }else{
            // If the file is already present, send the SetAppIcon request
            try {
                proxy.setappicon(ICON_FILENAME, autoIncCorrId++);
            } catch (SdlException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onPutFileResponse(PutFileResponse response) {
        Log.i(TAG, "onPutFileResponse from SDL");
        if(response.getCorrelationID().intValue() == iconCorrelationId){ //If we have successfully uploaded our icon, we want to set it
            try {
                proxy.setappicon(ICON_FILENAME, autoIncCorrId++);
            } catch (SdlException e) {
                e.printStackTrace();
            }
        }

    }

    @Override
    public void onOnLockScreenNotification(OnLockScreenStatus notification) {
        System.out.println("onOnLockScreenNotification : " + notification.getDriverDistractionStatus());
        return;
//
  //      if(!lockscreenDisplayed /*&& notification.getDriverDistractionStatus() == true*/ && notification.getShowLockScreen() == LockScreenStatus.REQUIRED){
  //          // Show lock screen
   //         Intent intent = new Intent(MapsActivity.getmContext(),LockScreenActivity.class);
   //         intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY|Intent.FLAG_ACTIVITY_NEW_TASK);
   //         lockscreenDisplayed = true;
    //        MapsActivity.getmContext().startActivity(intent);
    //    } /*else if(lockscreenDisplayed && notification.getShowLockScreen() != LockScreenStatus.REQUIRED){
    //        // Clear lock screen
    //        clearLockScreen();
    //    }*/
    }

    @Override
    public void onButtonPressResponse(ButtonPressResponse response) {

    }

    @Override
    public void onGetInteriorVehicleDataCapabilitiesResponse(GetInteriorVehicleDataCapabilitiesResponse response) {

    }

    private void clearLockScreen() {
        /*
        Intent intent = new Intent(MapsActivity.getmContext(), MapsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY|Intent.FLAG_ACTIVITY_NEW_TASK);
        MapsActivity.getmContext().startActivity(intent);
        lockscreenDisplayed = false;*/
    }

    @Override
    public void onOnCommand(OnCommand notification){
        Integer id = notification.getCmdID();
        if(id != null){
            switch(id){
            case TEST_COMMAND_ID:
                showTest();
                break;
            }
            //onAddCommandClicked(id);
        }
    }

    /**
     *  Callback method that runs when the add command response is received from SDL.
     */
    @Override
    public void onAddCommandResponse(AddCommandResponse response) {
        Log.i(TAG, "onAddCommandResponse: " + response);

    }


    /*  Vehicle Data   */


    @Override
    public void onOnPermissionsChange(OnPermissionsChange notification) {
        Log.i(TAG, "onOnPermissionsChange: " + notification);
        //System.out.println("MADE it HERE");
        // Uncomment to subscribe to vehicle data
        List<PermissionItem> permissions = notification.getPermissionItem();
        for(PermissionItem permission:permissions){
            if(permission.getRpcName().equalsIgnoreCase(FunctionID.SUBSCRIBE_VEHICLE_DATA.name())){
                if(permission.getHMIPermissions().getAllowed()!=null && permission.getHMIPermissions().getAllowed().size()>0){
                    if(!isVehicleDataSubscribed) { //If we haven't already subscribed we will subscribe now
                        //TODO: Add the vehicle data items you want to subscribe to
                        //proxy.subscribevehicledata(gps, speed, rpm, fuelLevel, fuelLevel_State, instantFuelConsumption, externalTemperature, prndl, tirePressure, odometer, beltStatus, bodyInformation, deviceStatus, driverBraking, correlationID);
                        //proxy.subscribevehicledata(false, true, true, false, false, false, false, false, false, false, false, false, false, false, autoIncCorrId++);
                        try {
                            System.out.println("SUBSCRIBED DATA!");
                            proxy.subscribevehicledata(false, true, true, false, false, false, true, true, false, false, false, false, false, true, autoIncCorrId++);
                        } catch (SdlException e){
                            System.out.println("Subscribe vehicle data error : "+e.toString());
                        }

                    }
                }
            }
        }
        //*/
    }

    @Override
    public void onSubscribeVehicleDataResponse(SubscribeVehicleDataResponse response) {
        if(response.getSuccess()){
            Log.i(TAG, "onSubscribeVehicleDataResponse");
            this.isVehicleDataSubscribed = true;
        }
    }

    @Override
    public void onOnVehicleData(OnVehicleData notification) {
        Log.i(TAG, "onOnVehicleData: " + notification);
        //TODO Put your vehicle data code here
        GPSData gpsData = notification.getGps();
        if(gpsData != null) {
            double latitudeD = gpsData.getLatitudeDegrees();
            double longitudeD = gpsData.getLongitudeDegrees();
            location = new AirRecircTriggered(latitudeD, longitudeD, getManualString(manual));
        }
    }
    public String getManualString(boolean man)
    {
        if(man)
            return "Manual";
        else
            return "Auto";
    }
    public void writeToDatabase(double longitudeD, double latitudeD) {
        int longI = (int) (longitudeD * 100);
        int latI = (int) (latitudeD * 100);
        int latURange = (int) ((latitudeD + radius / 69.0) * 100) - latI;
        int latLRange = latI - (int) ((latitudeD - radius / 69) * 100);
        int longURange = (int) ((longitudeD + radius / 69.0) * 100) - longI;
        int longLRange = longI - (int) ((longitudeD - radius / 69.0) * 100);
        String key = Util.ref.child("Position").push().getKey();
        //this loops and adds recirc through all possible gps locations that could be a distance "radius" from the current position
        int trueLong = longI;
        int trueLat = latI;
        for (int i = -latLRange; i <= latURange; i++) {
            if (latI + i >= 9000)
                trueLat = 9000;
            else if (latI + i <= -9000)
                trueLat = -9000;
            else
                trueLat = latI + i;
            for (int g = -longLRange; g <= longURange; g++) {
                if (longI + g >= 18000)
                    trueLong = longI + g - 36000;
                else if (longI + g <= -18000)
                    trueLong = longI + g + 36000;
                else
                    trueLong = longI + g;
                if(uniqueID != null)
                    Util.ref.child("Local").child(uniqueID).child("" + trueLong).child("" + trueLat).child(key).setValue(new AirRecircTriggered(longitudeD, latitudeD, getManualString(manual)));
                Util.ref.child("Position").child("" + trueLong).child("" + trueLat).child(key).setValue(new AirRecircTriggered(longitudeD, latitudeD, getManualString(manual)));
                //System.out.println("Position/" + trueLong + "/" + trueLat + "/" + key);
            }
        }
    }
    public void checkCircleRemove(LatLng latLng)
    {
        if(insideRadius != null)
        {
            if(locationWithinRange(new AirRecircTriggered(latLng.longitude, latLng.latitude, "manual"), insideRadius, radius, false))
            {
                turnOffAirRecirc();
                insideRadius = null;
            }
        }
    }
    public void removeFromDatabase(double longitudeD, double latitudeD, String key, DatabaseReference ref) {
        int longI = (int) (longitudeD * 100);
        int latI = (int) (latitudeD * 100);
        double tempradius = 2;
        int latURange = (int) ((latitudeD + tempradius / 69.0) * 100) - latI + 1;
        int latLRange = latI - (int) ((latitudeD - tempradius / 69) * 100);
        int longURange = (int) ((longitudeD + tempradius / 69.0) * 100) - longI + 1;
        int longLRange = longI - (int) ((longitudeD - tempradius / 69.0) * 100);
        //this loops and adds recirc through all possible gps locations that could be a distance "radius" from the current position
        int trueLong = longI;
        int trueLat = latI;
        for (int i = -latLRange; i <= latURange; i++) {
            if (latI + i >= 9000)
                trueLat = 9000;
            else if (latI + i <= -9000)
                trueLat = -9000;
            else
                trueLat = latI + i;
            for (int g = -longLRange; g <= longURange; g++) {
                if (longI + g >= 18000)
                    trueLong = longI + g - 36000;
                else if (longI + g <= -18000)
                    trueLong = longI + g + 36000;
                else
                    trueLong = longI + g;

                ref.child("Position").child("" + trueLong).child("" + trueLat).child(key).removeValue();
                if (uniqueID != null)
                    ref.child("Local").child(uniqueID).child("" + trueLong).child("" + trueLat).child(key).removeValue();
                System.out.println("Position/" + trueLong + "/" + trueLat + "/" + key);
            }
        }
    }
    public void writeToDatabase(double longitudeD, double latitudeD, boolean man, DatabaseReference ref)
    {
        int longI = (int)(longitudeD*100);
        int latI = (int)(latitudeD*100);
        int latURange = (int)((latitudeD + radius/69.0)*100)-latI + 1;
        int latLRange = latI - (int)((latitudeD - radius/69.0)*100);
        int longURange = (int)((longitudeD + radius/69.0)*100) - longI + 1;
        int longLRange = longI - (int)((longitudeD - radius/69.0)*100);
        String key = ref.child("Position").push().getKey();
        //this loops and adds recirc through all possible gps locations that could be a distance "radius" from the current position
        int trueLong = longI;
        int trueLat = latI;
        for(int i = -latLRange; i <= latURange;i++)
        {
            if(latI + i >= 9000)
                trueLat = 9000;
            else
            if(latI + i <= -9000)
                trueLat = -9000;
            else
                trueLat = latI+i;
            for(int g = -longLRange; g <= longURange; g++)
            {
                if(longI + g >= 18000)
                    trueLong = longI+g-36000;
                else
                if(longI + g <= -18000)
                    trueLong = longI+g+36000;
                else
                    trueLong = longI+g;
                ref.child("Position").child("" + trueLong).child("" + trueLat).child(key).setValue(new AirRecircTriggered(longitudeD,latitudeD, getManualString(man)));
                if(uniqueID != null)
                    ref.child("Local").child(uniqueID).child("" + trueLong).child("" + trueLat).child(key).setValue(new AirRecircTriggered(longitudeD, latitudeD, getManualString(manual)));
                //System.out.println("Position/" + trueLong + "/" + trueLat + "/" + key);
            }
        }
    }//write to database

    /**
     * Rest of the SDL callbacks from the head unit
     */

    @Override
    public void onAddSubMenuResponse(AddSubMenuResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onCreateInteractionChoiceSetResponse(CreateInteractionChoiceSetResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onAlertResponse(AlertResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onDeleteCommandResponse(DeleteCommandResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onDeleteInteractionChoiceSetResponse(DeleteInteractionChoiceSetResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onDeleteSubMenuResponse(DeleteSubMenuResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onPerformInteractionResponse(PerformInteractionResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onResetGlobalPropertiesResponse(
            ResetGlobalPropertiesResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onSetGlobalPropertiesResponse(SetGlobalPropertiesResponse response) {
    }

    @Override
    public void onSetMediaClockTimerResponse(SetMediaClockTimerResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onShowResponse(ShowResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onSpeakResponse(SpeakResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onOnButtonEvent(OnButtonEvent notification) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onOnButtonPress(OnButtonPress notification) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onSubscribeButtonResponse(SubscribeButtonResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onUnsubscribeButtonResponse(UnsubscribeButtonResponse response) {
        // TODO Auto-generated method stub
    }


    @Override
    public void onOnTBTClientState(OnTBTClientState notification) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onUnsubscribeVehicleDataResponse(
            UnsubscribeVehicleDataResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onGetVehicleDataResponse(GetVehicleDataResponse response) {
        // TODO Auto-generated method stub
        System.out.println("onGetVehicleDataResponse: " + response.toString());
        if (response.getResultCode() == Result.SUCCESS) {
            Hashtable<String, Object> moduleData = (Hashtable) response.getParameters("moduleData");
            Hashtable<String, Object> climateControldata = (Hashtable) moduleData.get("climateControlData");
            if (climateControldata != null) {


            }
        }
    }

    @Override
    public void onReadDIDResponse(ReadDIDResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onGetDTCsResponse(GetDTCsResponse response) {
        // TODO Auto-generated method stub

    }


    @Override
    public void onPerformAudioPassThruResponse(PerformAudioPassThruResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onEndAudioPassThruResponse(EndAudioPassThruResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onOnAudioPassThru(OnAudioPassThru notification) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onDeleteFileResponse(DeleteFileResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onSetAppIconResponse(SetAppIconResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onScrollableMessageResponse(ScrollableMessageResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onChangeRegistrationResponse(ChangeRegistrationResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onSetDisplayLayoutResponse(SetDisplayLayoutResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onOnLanguageChange(OnLanguageChange notification) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onSliderResponse(SliderResponse response) {
        // TODO Auto-generated method stub

    }


    @Override
    public void onOnHashChange(OnHashChange notification) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onOnSystemRequest(OnSystemRequest notification) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onSystemRequestResponse(SystemRequestResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onOnKeyboardInput(OnKeyboardInput notification) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onOnTouchEvent(OnTouchEvent notification) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onDiagnosticMessageResponse(DiagnosticMessageResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onOnStreamRPC(OnStreamRPC notification) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onStreamRPCResponse(StreamRPCResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onDialNumberResponse(DialNumberResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onSendLocationResponse(SendLocationResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onServiceEnded(OnServiceEnded serviceEnded) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onServiceNACKed(OnServiceNACKed serviceNACKed) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onShowConstantTbtResponse(ShowConstantTbtResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onAlertManeuverResponse(AlertManeuverResponse response) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onUpdateTurnListResponse(UpdateTurnListResponse response) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onServiceDataACK() {

    }

    @Override
    public void onGetInteriorVehicleDataResponse(GetInteriorVehicleDataResponse response) {
        System.out.println("onGetInteriorVehicleDataResponse : " + response.getResultCode());
        if (response.getResultCode() == Result.SUCCESS) {
            Hashtable<String, Object> moduleData = (Hashtable) response.getParameters("moduleData");
            Hashtable<String, Object> climateControldata = (Hashtable) moduleData.get("climateControlData");
            if (climateControldata != null) {
                currentTemp = (Integer) climateControldata.get("desiredTemp");
                if(!recircStateChanged) {
                    recirc = (Boolean) climateControldata.get("recirculateAirEnable");
                    recircStateChanged = true;
                }
                System.out.println("RecircVar Now : " + recirc);
                Log.e(TAG, "onGetInteriorVehicleDataResponse: Temp " + currentTemp);
            }
           /* Hashtable<String, Object> radioControldata = (Hashtable) moduleData.get("radioControlData");
            if (radioControldata != null) {
                signalStrength = (Integer) radioControldata.get("signalStrength");
                Log.e(TAG, "onGetInteriorVehicleDataResponse: signalStrength " + signalStrength);
                Hashtable<String, Object> rdsData = (Hashtable) radioControldata.get("rdsData");
                if (rdsData != null) {
                    radioText = (String) rdsData.get("RT");
                    if(radioText != null)
                        Log.e(TAG, "onGetInteriorVehicleDataResponse: radioText " + radioText);
                    programService = (String) rdsData.get("PS");
                    if(programService != null)
                        Log.e(TAG, "onGetInteriorVehicleDataResponse: programService " + programService);

                    PTY = (int) rdsData.get("PTY");
                    Log.e(TAG, "onGetInteriorVehicleDataResponse: PTY " + PTY);
                }
            }*/
        }
    }

    @Override
    public void onSetInteriorVehicleDataResponse(SetInteriorVehicleDataResponse response) {

    }

    @Override
    public void onOnInteriorVehicleData(OnInteriorVehicleData notification) {
        System.out.println("Interior Vehicle Data : " + notification.getModuleData());
        Log.e(TAG, "onOnInteriorVehicleData: " + notification.getModuleData());
        if(notification != null && notification.getModuleData() != null && notification.getModuleData().getControlData() != null) {
            if (notification.getModuleData().getModuleType().equals(ModuleType.CLIMATE)) {
                ClimateControlData cd = (ClimateControlData) notification.getModuleData().getControlData();
                currentTemp = (Integer) cd.getDesiredTemp();
                currentFanSpeed = cd.getFanSpeed();
                if (recirc != cd.getRecirculateEnabled()) {
                    //log data point here. Take gps location and recirc control
                    recirc = !recirc;
                    if(recirc) {
                        System.out.println("Recirc is ON");
                        if(location != null && !autoToggleAir)
                            writeToDatabase(location.longitude, location.latitude);
                        autoToggleAir = false;
                        manual = true;
                        //writeToDatabase(Math.random()*360-180, Math.random()*180-90);
                    }
                    else {
                        insideRadius = null;
                        System.out.println("Recirc is OFF");
                    }
                }
            }//notification is climate data
        }
    }

    @Override
    public void onOnDriverDistraction(OnDriverDistraction notification) {
        // Some RPCs (depending on region) cannot be sent when driver distraction is active.
        System.out.println("Hello : " + notification.getState());
        /*if(notification.getState().equals("DD_ON"))
            proxy.speak("Driver Distraction",autoIncCorrId++);*/
    }

    @Override
    public void onError(String info, Exception e) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onGenericResponse(GenericResponse response) {
        // TODO Auto-generated method stub
        System.out.println("General Response : " + response.getInfo());
    }

}
