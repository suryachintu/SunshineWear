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

package com.surya.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Displays the user's daily distance total via Google Fit. Distance is polled initially when the
 * Google API Client successfully connects and once a minute after that via the onTimeTick callback.
 * If you want more frequent updates, you will want to add your own  Handler.
 *
 * Authentication IS a requirement to request distance from Google Fit on Wear. Otherwise, distance
 * will always come back as zero (or stay at whatever the distance was prior to you
 * de-authorizing watchface). To authenticate and communicate with Google Fit, you must create a
 * project in the Google Developers Console, activate the Fitness API, create an OAuth 2.0
 * client ID, and register the public certificate from your app's signed APK. More details can be
 * found here: https://developers.google.com/fit/android/get-started#step_3_enable_the_fitness_api
 *
 * In ambient mode, the seconds are replaced with an AM/PM indicator.
 *
 * On devices with low-bit ambient mode, the text is drawn without anti-aliasing. On devices which
 * require burn-in protection, the hours are drawn in normal rather than bold.
 *
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String TAG = "SunshineWatchFace";

    /**
     * Update rate in milliseconds for active mode (non-ambient).
     */
    private static final long ACTIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener{

        private static final int BACKGROUND_COLOR = Color.BLACK;

        private static final String COLON_STRING = ":";

        private static final int MSG_UPDATE_TIME = 0;

        /* Handler to update the time periodically in interactive mode. */
        private final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        Log.v(TAG, "updating time");
                        invalidate();
                        if (shouldUpdateTimeHandlerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    ACTIVE_INTERVAL_MS - (timeMs % ACTIVE_INTERVAL_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };


        /**
         * Handles time zone and locale changes.
         */
        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /**
         * Unregistering an unregistered receiver throws an exception. Keep track of the
         * registration state to prevent that.
         */
        private boolean mRegisteredReceiver = false;

        private Paint mHourPaint;
        private Paint mMinutePaint;
        private Paint mColonPaint;
        private Paint mLinePaint;
        private Paint mHighPaint;
        private Paint mLowPaint;
        private Paint mTodayPaint;

        private Calendar mCalendar;

        private float mYOffset;
        private float mLineHeight;
        private float mLineLength;
        private float mMultiplier;

        //strings for storing data
        private String mHighText ;
        private String mLowText;
        private int  mWeatherId;



        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;

        private Bitmap mArt;
        private GoogleApiClient googleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            Log.d(TAG, "onCreate");

            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            googleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            Resources resources = getResources();

            mHighText="0";
            mLowText="0";
            mWeatherId = -1;
            mLineHeight = resources.getDimension(R.dimen.fit_line_height);
            mLineLength = resources.getDimension(R.dimen.fit_line_length);

            mHourPaint = createTextPaint();
            mHighPaint = createTextPaint();
            mMinutePaint = createTextPaint();
            mLowPaint = createTextPaint();
            mLinePaint = createTextPaint();
            mColonPaint = createTextPaint();
            mTodayPaint = createTextPaint();

            mCalendar = Calendar.getInstance();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            Log.d(TAG, "onVisibilityChanged: " + visible);

            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                googleApiClient.connect();
                Wearable.DataApi.addListener(googleApiClient,this);
                Wearable.DataApi.getDataItems(googleApiClient).setResultCallback(onConnectedResultCallback);
                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
                googleApiClient.disconnect();
                Wearable.DataApi.removeListener(googleApiClient,this);
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        private void registerReceiver() {
            if (mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));

            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.fit_text_size_round : R.dimen.fit_text_size);
            float temp_textSize = resources.getDimension(isRound ? R.dimen.fit_temp_text_size_round : R.dimen.fit__temp_text_size);

            mYOffset = resources.getDimension(isRound ? R.dimen.fit_y_offset_round : R.dimen.fit_y_offset);

            mMultiplier = isRound ? 2.2f : 2.15f;
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mHighPaint.setTextSize(temp_textSize);
            mLowPaint.setTextSize(temp_textSize);
            mTodayPaint.setTextSize(
                    resources.getDimension(R.dimen.fit_steps_or_distance_text_size));

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            Log.d(TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
                    + ", low-bit ambient = " + mLowBitAmbient);

        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
                mTodayPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFace.this);
            // Draw the background.
            if (isInAmbientMode()){
                canvas.drawColor(BACKGROUND_COLOR);
            }else {
                canvas.drawColor(getColor(R.color.primary));
            }
            // Draw the hours.
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            canvas.drawText(hourString, bounds.centerX() - (mColonPaint.measureText(COLON_STRING)+mHourPaint.measureText(hourString)), mYOffset, mHourPaint);

            // Draw first colon (between hour and minute).
            canvas.drawText(COLON_STRING,bounds.centerX()- (mColonPaint.measureText(COLON_STRING))/2, mYOffset, mColonPaint);

            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, bounds.centerX() + (mColonPaint.measureText(COLON_STRING)), mYOffset, mMinutePaint);

            // In interactive mode, draw a second colon followed by the seconds.
            // Otherwise, if we're in 12-hour mode, draw AM/PM
            if (!isInAmbientMode()) {
                SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d yyyy", Locale.ENGLISH);
                String date = sdf.format(Calendar.getInstance().getTime());

                canvas.drawText(date,bounds.centerX()- (mTodayPaint.measureText(date))/2,mYOffset+mLineHeight, mTodayPaint);
                canvas.drawLine(bounds.centerX() - mLineLength,mYOffset + 1.5f*mLineHeight,bounds.centerX() + mLineLength,mYOffset + 1.5f*mLineHeight,mLinePaint);

                canvas.drawText(getString(R.string.format_temperature, mHighText), bounds.centerX() - mHighPaint.measureText(mHighText), mMultiplier * mYOffset, mHighPaint);
                if (mArt !=null)
                    canvas.drawBitmap(mArt, bounds.centerX() - (mArt.getWidth() + 1.5f * mHighPaint.measureText(mHighText)), 1.8f * mYOffset, null);

                canvas.drawText(getString(R.string.format_temperature, mLowText), bounds.centerX() + (mLowPaint.measureText(mLowText)), mMultiplier * mYOffset, mLowPaint);

            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            Log.d(TAG, "updateTimer");

            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldUpdateTimeHandlerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldUpdateTimeHandlerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.e(TAG,"Data changed");
            for (DataEvent event:
                    dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED){
                    DataItem dataItem = event.getDataItem();
                    updateWatchFace(dataItem);
                }
            }
            dataEventBuffer.release();
        }

        private final ResultCallback<DataItemBuffer> onConnectedResultCallback = new ResultCallback<DataItemBuffer>() {
            @Override
            public void onResult(DataItemBuffer dataItems) {
                for (DataItem item : dataItems) {
                    updateWatchFace(item);
                }
                dataItems.release();
            }
        };


        private void updateWatchFace(DataItem dataItem) {
            if (dataItem.getUri().getPath().equals("/sunshine_temp")){
                DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                if (dataMap.containsKey("temp")){
                    Log.e(TAG,dataMap.getString("temp"));
                    String[] highLow = dataMap.getString("temp").split(",");
                    mHighText = highLow[0];
                    mLowText = highLow[1];
                    mWeatherId = Integer.parseInt(highLow[2]);
                    scaleBitmap();
                }
            }
            invalidate();
        }

        private void scaleBitmap() {
            Drawable drawable = getResources().getDrawable(getIconResourceForWeatherCondition(mWeatherId));

            mArt = ((BitmapDrawable)drawable).getBitmap();

            mArt = Bitmap.createScaledBitmap(mArt,56,56,true);
        }

        private int getIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.e(TAG,"Api connected");
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.e(TAG,"Api connection suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(TAG,"Api connection failed" + connectionResult.toString());
        }

    }

}