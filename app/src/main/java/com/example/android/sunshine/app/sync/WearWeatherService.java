package com.example.android.sunshine.app.sync;

import android.util.Log;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

public class WearWeatherService extends WearableListenerService {

    private static final String TAG = WearWeatherService.class.getSimpleName();

    private static final String WEATHER_REQ_PATH = "/weather-req";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent dataEvent : dataEvents) {
            if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                String path = dataEvent.getDataItem().getUri().getPath();
                Log.d(TAG, path);
                if (path.equals(WEATHER_REQ_PATH)) {
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }
}