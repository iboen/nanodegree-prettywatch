/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.ColorRes;
import android.support.annotation.DimenRes;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static final String WEATHER_REQ_PATH = "/weather-req";
    private static final String WEATHER_INFO_PATH = "/weather-info";
    private static final String KEY_ID = "id";
    private static final String KEY_HIGH = "high";
    private static final String KEY_LOW = "low";
    private static final String KEY_WEATHER_ID = "weatherId";

    /**
     * Helper method to provide the art resource id according to the weather condition id returned
     * by the OpenWeatherMap call.
     *
     * @param weatherId from OpenWeatherMap API response
     * @return resource id for the corresponding icon. -1 if no relation is found.
     */
    public static int getArtResourceForWeatherCondition(int weatherId) {
        // Based on weather code data found at:
        // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.art_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.art_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.art_rain;
        } else if (weatherId == 511) {
            return R.drawable.art_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.art_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.art_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.art_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.art_storm;
        } else if (weatherId == 800) {
            return R.drawable.art_clear;
        } else if (weatherId == 801) {
            return R.drawable.art_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.art_clouds;
        }
        return -1;
    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener {
        private static final String TAG = "ENGINE";
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint, mBlackPaint;
        Paint mTextPaintClock, mTextPaintDate, mTextPaintTempHigh, mTextPaintTempLow;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        float mCenterX;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private int mWidth;

        private String mWeatherHigh;
        private String mWeatherLow;
        private Bitmap mWeatherIcon;
        private Bitmap mGrayWeatherIcon;
        private Rect mCardBounds = new Rect();

        private SharedPreferences mPref;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle bundle) {
                        Log.d(TAG, "onConnected: " + bundle);
                        Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

                        requestWeatherInfo();
                    }

                    @Override
                    public void onConnectionSuspended(int cause) {
                        Log.d(TAG, "onConnectionSuspended: " + cause);
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult connectionResult) {
                        Log.d(TAG, "onConnectionFailed: " + connectionResult);
                    }
                })
                .addApi(Wearable.API)
                .build();

        private void requestWeatherInfo() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_REQ_PATH);
            putDataMapRequest.getDataMap().putString(KEY_ID, UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "Failed request weather data from companion device");
                            } else {
                                Log.d(TAG, "Successfully request for weather data");
                            }
                        }
                    });
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));
            mBlackPaint = new Paint();
            mBlackPaint.setColor(Color.BLACK);

            mTextPaintClock = createTextPaint(R.color.digital_text_white, R.dimen.digital_text_size_clock);
            mTextPaintDate = createTextPaint(R.color.digital_text_semi_white, R.dimen.digital_text_size_date);
            mTextPaintTempHigh = createTextPaint(R.color.digital_text_white, R.dimen.digital_text_size_temp);
            mTextPaintTempLow = createTextPaint(R.color.digital_text_semi_white, R.dimen.digital_text_size_temp);

            mTime = new Time();

            //get from pref first
            mPref = PreferenceManager.getDefaultSharedPreferences(MyWatchFace.this);
            mWeatherLow = mPref.getString(KEY_LOW, null);
            mWeatherHigh = mPref.getString(KEY_HIGH, null);
            mWeatherIcon = getWeatherIconBitmap(mPref.getInt(KEY_WEATHER_ID, 0));
        }

        private Paint createTextPaint(@ColorRes int textColor, @DimenRes int resTextSize) {
            Paint paint = new Paint();
            setTextPaintColor(paint, textColor);
            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(NORMAL_TYPEFACE);
            float textSize = MyWatchFace.this.getResources().getDimension(resTextSize);
            paint.setTextSize(textSize);
            return paint;

        }

        private void setTextPaintColor(Paint paint, @ColorRes int textColor) {
            if (!mAmbient) {
                paint.setColor(MyWatchFace.this.getResources().getColor(textColor));
            } else {
                paint.setColor(Color.WHITE);
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            mCardBounds.set(rect);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            mWidth = width;

            mCenterX = mWidth / 2f;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaintDate.setAntiAlias(!inAmbientMode);
                    mTextPaintClock.setAntiAlias(!inAmbientMode);
                    mTextPaintTempHigh.setAntiAlias(!inAmbientMode);
                    mTextPaintTempLow.setAntiAlias(!inAmbientMode);
                }

                setTextPaintColor(mTextPaintDate, R.color.digital_text_semi_white);
                setTextPaintColor(mTextPaintTempLow, R.color.digital_text_semi_white);

                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

            String text = String.format("%02d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(text, mCenterX, mYOffset, mTextPaintClock);

            float yPos = mYOffset + 30;
            text = mTime.format("%a, %b %d %Y").toUpperCase();
            canvas.drawText(text, mCenterX, yPos, mTextPaintDate);

            yPos = yPos + 30;
            canvas.drawLine(mCenterX - 30, yPos, mCenterX + 30, yPos, mTextPaintDate);

            yPos = yPos + 50;
            if (mWeatherHigh != null && mWeatherLow != null) {
                canvas.drawText(mWeatherHigh, mCenterX, yPos, mTextPaintTempHigh);
                float highLength = mTextPaintTempHigh.measureText(mWeatherHigh);
                canvas.drawText(mWeatherLow, mCenterX + highLength + 10, yPos, mTextPaintTempLow);

                if (!mLowBitAmbient) {
                    Bitmap bmpWeather = (!mAmbient) ? mWeatherIcon : mGrayWeatherIcon;
                    if (bmpWeather != null)
                        canvas.drawBitmap(bmpWeather, mCenterX - (highLength + bmpWeather.getHeight()),
                                yPos - bmpWeather.getHeight() + 15, null);
                }
            }

            if (mAmbient) {
                canvas.drawRect(mCardBounds, mBlackPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(WEATHER_INFO_PATH) == 0) {

                        SharedPreferences.Editor editor = mPref.edit();

                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        if (dataMap.containsKey(KEY_HIGH)) {
                            mWeatherHigh = dataMap.getString(KEY_HIGH);
                            editor.putString(KEY_HIGH, mWeatherHigh);
                        }

                        if (dataMap.containsKey(KEY_LOW)) {
                            mWeatherLow = dataMap.getString(KEY_LOW);
                            editor.putString(KEY_LOW, mWeatherLow);
                        }

                        if (dataMap.containsKey(KEY_WEATHER_ID)) {
                            int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                            editor.putInt(KEY_WEATHER_ID, weatherId);

                            mWeatherIcon = getWeatherIconBitmap(weatherId);
                        }

                        editor.commit();

                        invalidate();
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                    mPref.edit().clear().commit();
                }
            }
        }

        private Bitmap getWeatherIconBitmap(int weatherId) {
            if (weatherId > 0) {
                Drawable b = getResources().getDrawable(getArtResourceForWeatherCondition(weatherId));
                Bitmap icon = ((BitmapDrawable) b).getBitmap();

                Bitmap resBitmap = Bitmap.createScaledBitmap(icon, 60, 60, true);

                //clone for Ambient mode
                mGrayWeatherIcon = Bitmap.createBitmap(
                        resBitmap.getWidth(),
                        resBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(mGrayWeatherIcon);
                Paint grayPaint = new Paint();
                ColorMatrix colorMatrix = new ColorMatrix();
                colorMatrix.setSaturation(0);
                ColorMatrixColorFilter filter = new
                        ColorMatrixColorFilter(colorMatrix);
                grayPaint.setColorFilter(filter);
                canvas.drawBitmap(resBitmap, 0, 0, grayPaint);

                return resBitmap;
            } else {
                return null;
            }
        }
    }
}
