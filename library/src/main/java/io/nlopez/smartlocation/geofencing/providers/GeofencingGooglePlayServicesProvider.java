package io.nlopez.smartlocation.geofencing.providers;

import android.app.Activity;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.*;
import android.os.Bundle;
import android.support.annotation.NonNull;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;
import com.google.android.gms.location.LocationServices;
import io.nlopez.smartlocation.OnGeofencingTransitionListener;
import io.nlopez.smartlocation.geofencing.GeofencingProvider;
import io.nlopez.smartlocation.geofencing.GeofencingStore;
import io.nlopez.smartlocation.geofencing.model.GeofenceModel;
import io.nlopez.smartlocation.utils.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by mrm on 3/1/15.
 */
public class GeofencingGooglePlayServicesProvider implements GeofencingProvider, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, ResultCallback<Status> {

    public static final int RESULT_CODE = 10003;

    private static final String GMS_ID = "GMS";
    private static final String BROADCAST_INTENT_ACTION = GeofencingGooglePlayServicesProvider.class.getCanonicalName() + ".GEOFENCE_TRANSITION";
    private static final String GEOFENCES_EXTRA_ID = "geofences";
    private static final String TRANSITION_EXTRA_ID = "transition";
    private static final String LOCATION_EXTRA_ID = "location";

    private final List<Geofence> geofencesToAdd = new ArrayList<>();
    private final List<String> geofencesToRemove = new ArrayList<>();

    private GoogleApiClient client;
    private Logger logger;
    private OnGeofencingTransitionListener listener;
    private GeofencingStore geofencingStore;
    private Context context;
    private PendingIntent pendingIntent;

    @Override
    public void init(@NonNull Context context, Logger logger) {
        this.context = context;
        this.logger = logger;

        geofencingStore = new GeofencingStore(context);

        IntentFilter intentFilter = new IntentFilter(BROADCAST_INTENT_ACTION);
        context.registerReceiver(geofencingReceiver, intentFilter);

        this.client = new GoogleApiClient.Builder(context)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        client.connect();

        pendingIntent = PendingIntent.getService(context, 0, new Intent(context, GeofencingService.class),
                                                 PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void addGeofence(GeofenceModel geofence) {
        List<GeofenceModel> wrapperList = new ArrayList<>();
        wrapperList.add(geofence);
        addGeofences(wrapperList);
    }

    @Override
    public void addGeofences(List<GeofenceModel> geofenceList) {
        synchronized (geofencesToAdd) {
            List<Geofence> convertedGeofences = new ArrayList<>();
            for (GeofenceModel geofenceModel : geofenceList) {
                geofencingStore.put(geofenceModel.getRequestId(), geofenceModel);
                convertedGeofences.add(geofenceModel.toGeofence());
            }

            if (client.isConnected()) {
                if (geofencesToAdd.size() > 0) {
                    convertedGeofences.addAll(geofencesToAdd);
                    geofencesToAdd.clear();
                }
                LocationServices.GeofencingApi.addGeofences(client, convertedGeofences, pendingIntent);

            } else {
                for (GeofenceModel geofenceModel : geofenceList) {
                    geofencesToAdd.add(geofenceModel.toGeofence());
                }
            }
        }
    }

    @Override
    public void removeGeofence(String geofenceId) {
        List<String> wrapperList = new ArrayList<>();
        wrapperList.add(geofenceId);
        removeGeofences(wrapperList);
    }

    @Override
    public void removeGeofences(List<String> geofenceIds) {
        synchronized (geofencesToRemove) {
            for (String id : geofenceIds) {
                geofencingStore.remove(id);
            }

            if (client.isConnected()) {
                if (geofencesToRemove.size() > 0) {
                    geofenceIds.addAll(geofencesToRemove);
                    geofencesToRemove.clear();
                }
                LocationServices.GeofencingApi.removeGeofences(client, geofenceIds);
            } else {
                geofencesToRemove.addAll(geofenceIds);
            }
        }
    }

    @Override
    public void start(OnGeofencingTransitionListener listener) {
        this.listener = listener;

        if (!client.isConnected()) {
            logger.d("still not connected - scheduled start when connection is ok");
        }
    }

    @Override
    public void stop() {
        logger.d("stop");
        if (client.isConnected()) {
            client.disconnect();
        }
        try {
            context.unregisterReceiver(geofencingReceiver);
        } catch (IllegalArgumentException e) {
            logger.d("Silenced 'receiver not registered' stuff (calling stop more times than necessary did this)");
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        logger.d("onConnected");

        // TODO wait until the connection is done and retry
        if (client.isConnected()) {
            if (geofencesToAdd.size() > 0) {
                LocationServices.GeofencingApi.addGeofences(client, geofencesToAdd, pendingIntent);
                geofencesToAdd.clear();
            }

            if (geofencesToRemove.size() > 0) {
                LocationServices.GeofencingApi.removeGeofences(client, geofencesToRemove);
                geofencesToRemove.clear();
            }
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        logger.d("onConnectionSuspended " + i);
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        logger.d("onConnectionFailed");

    }

    private BroadcastReceiver geofencingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BROADCAST_INTENT_ACTION.equals(intent.getAction()) && intent.hasExtra(GEOFENCES_EXTRA_ID)) {
                logger.d("geofencing event");
                // TODO handle this
                //DetectedActivity detectedActivity = intent.getParcelableExtra(DETECTED_ACTIVITY_EXTRA_ID);
                //notifyActivity(detectedActivity);
            }
        }
    };

    public static class GeofencingService extends IntentService {

        public GeofencingService() {
            super(GeofencingService.class.getSimpleName());
        }

        @Override
        protected void onHandleIntent(Intent intent) {
            // TODO this - http://developer.android.com/training/location/geofencing.html
            GeofencingEvent geofencingEvent = GeofencingEvent.fromIntent(intent);
            if (!geofencingEvent.hasError()) {
                int transition = geofencingEvent.getGeofenceTransition();

                // Broadcast an intent containing the geofencing info
                Intent geofenceIntent = new Intent(BROADCAST_INTENT_ACTION);
                geofenceIntent.putExtra(TRANSITION_EXTRA_ID, transition);
                geofenceIntent.putExtra(LOCATION_EXTRA_ID, geofencingEvent.getTriggeringLocation());
                ArrayList<String> geofencingIds = new ArrayList<>();
                for (Geofence geofence : geofencingEvent.getTriggeringGeofences()) {
                    geofencingIds.add(geofence.getRequestId());
                }
                geofenceIntent.putStringArrayListExtra(GEOFENCES_EXTRA_ID, geofencingIds);
                sendBroadcast(geofenceIntent);
            }
        }
    }

    @Override
    public void onResult(Status status) {
        if (status.isSuccess()) {
            logger.d("Geofencing update request successful");
        } else if (status.hasResolution() && context instanceof Activity) {
            logger.w(
                    "Unable to register, but we can solve this - will startActivityForResult expecting result code " + RESULT_CODE + " (if received, please try again)");

            try {
                status.startResolutionForResult((Activity) context, RESULT_CODE);
            } catch (IntentSender.SendIntentException e) {
                logger.e(e, "problem with startResolutionForResult");
            }
        } else {
            // No recovery. Weep softly or inform the user.
            logger.e("Registering failed: " + status.getStatusMessage());
        }
    }

}
