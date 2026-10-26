package com.example.locationlogger;

import android.app.Service;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.Priority;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LocationLoggerService extends Service {
    private static final String TAG = "LocationLogger";
    private static final long LOCATION_UPDATE_INTERVAL = 5 * 60 * 1000; // 5 minutes

    private final IBinder binder = new LocalBinder();
    private FusedLocationProviderClient fusedLocationClient;
    private Geocoder geocoder;
    private String currentAddress;
    private File logFile;
    
    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            if (locationResult == null) return;
            
            try {
                if (locationResult.getLastLocation() != null) {
                    List<Address> addresses = geocoder.getFromLocation(
                            locationResult.getLastLocation().getLatitude(),
                            locationResult.getLastLocation().getLongitude(),
                            1);
                    
                    String address;
                    if (addresses != null && !addresses.isEmpty()) {
                        address = formatAddress(addresses.get(0));
                    } else {
                        address = "Coordinates: " + locationResult.getLastLocation().getLatitude() + 
                                ", " + locationResult.getLastLocation().getLongitude();
                    }
                    
                    if (!address.equals(currentAddress)) {
                        currentAddress = address;
                        logAddress(address);
                        Log.d(TAG, "Address changed: " + address);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting address", e);
            }
        }
    };

    public class LocalBinder extends Binder {
        public LocationLoggerService getService() {
            return LocationLoggerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = new FusedLocationProviderClient(this);
        geocoder = new Geocoder(this, Locale.getDefault());
        logFile = new File(getExternalFilesDir(null), "location.txt");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startLocationUpdates();
        return START_STICKY;
    }

    private void startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL).build();
        
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "Location updates started");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }
    }

    private String formatAddress(Address address) {
        StringBuilder sb = new StringBuilder();
        
        if (address.getThoroughfare() != null) {
            sb.append(address.getThoroughfare()).append(", ");
        }
        if (address.getSubThoroughfare() != null) {
            sb.append(address.getSubThoroughfare()).append(", ");
        }
        if (address.getLocality() != null) {
            sb.append(address.getLocality()).append(", ");
        }
        if (address.getAdminArea() != null) {
            sb.append(address.getAdminArea()).append(" ");
        }
        if (address.getPostalCode() != null) {
            sb.append(address.getPostalCode()).append(", ");
        }
        if (address.getCountryName() != null) {
            sb.append(address.getCountryName());
        }
        
        return sb.toString().replaceAll(", $", "");
    }

    private void logAddress(String address) {
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String logEntry = "[" + timestamp + "] " + address + "\n";
            
            java.nio.file.Files.write(logFile.toPath(), logEntry.getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, 
                    java.nio.file.StandardOpenOption.APPEND);
            
            Log.d(TAG, "Logged: " + logEntry);
        } catch (Exception e) {
            Log.e(TAG, "Error writing to log file", e);
        }
    }

    public void stopLogging() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
        Log.d(TAG, "Location updates stopped");
    }

    @Override
    public void onDestroy() {
        stopLogging();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
