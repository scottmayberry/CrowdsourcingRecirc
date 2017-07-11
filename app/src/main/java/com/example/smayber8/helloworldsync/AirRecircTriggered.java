package com.example.smayber8.helloworldsync;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.Date;

/**
 * Created by SMAYBER8 on 6/12/2017.
 */
@IgnoreExtraProperties
public class AirRecircTriggered {

    public double longitude;
    public double latitude;
    public String from;

    public AirRecircTriggered() {
        // Default constructor required for calls to DataSnapshot.getValue(User.class)
    }

    public AirRecircTriggered(double longitude, double latitude, String from) {
        this.longitude = longitude;
        this.latitude = latitude;
        this.from = from;
    }

}

