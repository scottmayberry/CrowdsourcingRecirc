package com.example.smayber8.helloworldsync;

/**
 * Created by SMAYBER8 on 6/30/2017.
 */

public class LatLngInt {
    private int latitude;

    public int getLatitude() {
        return latitude;
    }

    public void setLatitude(int latitude) {
        this.latitude = latitude;
    }

    public int getLongitude() {
        return longitude;
    }

    public void setLongitude(int longitude) {
        this.longitude = longitude;
    }

    private int longitude;

    public LatLngInt() {
    }

    public LatLngInt(int latitude, int longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
