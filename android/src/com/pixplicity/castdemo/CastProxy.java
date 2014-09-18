package com.pixplicity.castdemo;

import java.io.IOException;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.media.MediaRouter.RouteInfo;
import android.util.Log;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.Cast.ApplicationConnectionResult;
import com.google.android.gms.cast.Cast.MessageReceivedCallback;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

public class CastProxy {

    private static final String TAG = CastProxy.class.getSimpleName();

    private static CastProxy sInstance;
    private static int sInstanceCount;

    private final MediaRouter mMediaRouter;
    private final MediaRouteSelector mMediaRouteSelector;
    private final MediaRouter.Callback mMediaRouterCallback;
    private CastDevice mSelectedDevice;

    private GoogleApiClient mApiClient;
    private Cast.Listener mCastListener;
    private ConnectionCallbacks mConnectionCallbacks;
    private ConnectionFailedListener mConnectionFailedListener;

    private CastChannel mChannel;
    private boolean mWaitingForReconnect;
    private boolean mApplicationStarted;
    private String mSessionId;

    private final boolean mSingleUserMode = false;

    private final Context mApplicationContext;

    public static interface CastChannel extends MessageReceivedCallback {

        public String getNamespace();

        public void onConnected();

        public void onReconnected();

        public void onDisconnected();

        public boolean sendMessage(String message);

    }

    public CastProxy(Context applicationContext, CastChannel castChannel) {
        mApplicationContext = applicationContext;
        // Configure Cast device discovery
        mMediaRouter = MediaRouter.getInstance(applicationContext);
        mMediaRouteSelector = new MediaRouteSelector.Builder()
                .addControlCategory(
                        CastMediaControlIntent.categoryForCast(
                                applicationContext.getResources().getString(R.string.app_id)))
                .build();
        // Callback for MediaRouter events
        mMediaRouterCallback = new MediaRouter.Callback() {

            @Override
            public void onRouteSelected(MediaRouter router, RouteInfo info) {
                Log.d(TAG, "onRouteSelected");
                // Handle the user route selection.
                mSelectedDevice = CastDevice.getFromBundle(info.getExtras());
                // Launch the receiver app
                connect();
            }

            @Override
            public void onRouteUnselected(MediaRouter router, RouteInfo info) {
                Log.d(TAG, "onRouteUnselected: info=" + info);
                disconnect();
            }

        };
        setChannel(castChannel);
    }

    private void setChannel(CastChannel castChannel) {
        mChannel = castChannel;
    }

    /**
     * Connect to a device
     * 
     * @param device
     *            Device to launch onto
     */
    private void connect() {
        Log.d(TAG, "connecting...");
        try {
            mCastListener = new Cast.Listener() {

                @Override
                public void onApplicationDisconnected(int errorCode) {
                    Log.d(TAG, "application has stopped; errorCode=" + errorCode);
                    disconnect();
                }

            };
            // Connect to Google Play services
            mConnectionCallbacks = new ConnectionCallbacks();
            mConnectionFailedListener = new ConnectionFailedListener();
            Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions
                    .builder(mSelectedDevice, mCastListener);
            if (mApiClient != null) {
                Log.d(TAG, "existing API client during connection; disconnecting first...");
                // It's possible that we already had a connection; disconnect first to avoid a
                // conflicted state
                disconnect();
            }
            mApiClient = new GoogleApiClient.Builder(mApplicationContext)
                    .addApi(Cast.API, apiOptionsBuilder.build())
                    .addConnectionCallbacks(mConnectionCallbacks)
                    .addOnConnectionFailedListener(mConnectionFailedListener)
                    .build();

            mApiClient.connect();
        } catch (Exception e) {
            Log.e(TAG, "Failed launchReceiver", e);
        }
    }

    /**
     * Disconnect from the receiver
     */
    private void disconnect() {
        Log.d(TAG, "disconnecting...");
        if (mApiClient != null) {
            if (mApplicationStarted) {
                if (mApiClient.isConnected() || mApiClient.isConnecting()) {
                    try {
                        if (mSingleUserMode) {
                            Cast.CastApi.stopApplication(mApiClient, mSessionId);
                        } else {
                            Cast.CastApi.leaveApplication(mApiClient);
                        }
                        Cast.CastApi.removeMessageReceivedCallbacks(
                                mApiClient,
                                mChannel.getNamespace());
                    } catch (IOException e) {
                        Log.e(TAG, "Exception while removing channel", e);
                    }
                    mApiClient.disconnect();
                    mChannel.onDisconnected();
                }
                mApplicationStarted = false;
            }
            mApiClient = null;
        }
        mSelectedDevice = null;
        mWaitingForReconnect = false;
        mSessionId = null;
    }

    /**
     * Google Play services callbacks
     */
    private class ConnectionCallbacks implements
            GoogleApiClient.ConnectionCallbacks {

        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG, "onConnected");

            if (mApiClient == null) {
                // We got disconnected while this runnable was pending execution.
                Log.d(TAG, "disconnected during onConnected");
                return;
            }

            try {
                if (mWaitingForReconnect) {
                    mWaitingForReconnect = false;

                    // Check if the receiver app is still running
                    if (connectionHint != null
                            && connectionHint.getBoolean(Cast.EXTRA_APP_NO_LONGER_RUNNING)) {
                        Log.d(TAG, "App is no longer running");
                        disconnect();
                    } else {
                        // Re-create the custom message channel
                        createMessageChannel();
                    }
                } else {
                    launchApp(!mSingleUserMode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch application", e);
            }
        }

        private void launchApp(final boolean tryJoin) {
            // Launch the receiver app
            PendingResult<ApplicationConnectionResult> result;
            if (tryJoin) {
                result = Cast.CastApi.joinApplication(mApiClient, mApplicationContext.getString(R.string.app_id),
                        mSessionId);
            } else {
                result = Cast.CastApi.launchApplication(mApiClient, mApplicationContext.getString(R.string.app_id),
                        false);
            }
            result.setResultCallback(new ResultCallback<Cast.ApplicationConnectionResult>() {

                @Override
                public void onResult(ApplicationConnectionResult result) {
                    Status status = result.getStatus();
                    Log.d(TAG,
                            "ApplicationConnectionResultCallback.onResult: statusCode="
                                    + status.getStatusCode());
                    if (status.isSuccess()) {
                        // Just some debug information
                        ApplicationMetadata applicationMetadata = result.getApplicationMetadata();
                        mSessionId = result.getSessionId();
                        String applicationStatus = result.getApplicationStatus();
                        boolean wasLaunched = result.getWasLaunched();
                        Log.d(TAG, "application name: "
                                + applicationMetadata.getName()
                                + ", status: "
                                + applicationStatus
                                + ", sessionId: "
                                + mSessionId
                                + ", wasLaunched: "
                                + wasLaunched);

                        // Create the custom message channel
                        createMessageChannel();

                        if (!mApplicationStarted) {
                            // Allow the channel to perform connection events
                            mChannel.onConnected();
                        }

                        mApplicationStarted = true;

                    } else {
                        if (tryJoin
                                && status.getStatusCode() == CastStatusCodes.APPLICATION_NOT_RUNNING) {
                            launchApp(false);
                            return;
                        }
                        Log.e(TAG, "application could not launch");
                        disconnect();
                    }
                }
            });
        }

        private void createMessageChannel() {
            try {
                Cast.CastApi.setMessageReceivedCallbacks(
                        mApiClient,
                        mChannel.getNamespace(),
                        mChannel);
            } catch (IOException e) {
                Log.e(TAG, "Exception while creating channel", e);
            }
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "onConnectionSuspended");
            mWaitingForReconnect = true;
        }
    }

    /**
     * Google Play services callbacks
     */
    private class ConnectionFailedListener implements
            GoogleApiClient.OnConnectionFailedListener {

        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.e(TAG, "onConnectionFailed ");
            disconnect();
        }
    }

    public synchronized static CastProxy init(Context applicationContext, CastChannel castChannel) {
        if (sInstance == null && applicationContext != null) {
            sInstance = new CastProxy(applicationContext, castChannel);
        }
        sInstance.setChannel(castChannel);
        return sInstance;
    }

    public synchronized static void registerInstance() {
        if (sInstance != null) {
            if (sInstanceCount == 0) {
                // Start media router discovery
                sInstance.mMediaRouter.addCallback(sInstance.mMediaRouteSelector, sInstance.mMediaRouterCallback,
                        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
                if (sInstance.mSelectedDevice != null && sInstance.mApiClient == null) {
                    sInstance.connect();
                }
            } else if (sInstance.mSelectedDevice != null && sInstance.mApiClient != null) {
                // Inform the channel that we've reconnected
                sInstance.mChannel.onReconnected();
            }
            sInstanceCount++;
        }
    }

    public synchronized static void unregisterInstance(boolean disconnectIfLast) {
        sInstanceCount--;
        if (sInstanceCount <= 0) {
            if (sInstance != null) {
                // End media router discovery
                sInstance.mMediaRouter.removeCallback(sInstance.mMediaRouterCallback);
                if (disconnectIfLast) {
                    // Disconnect
                    sInstance.disconnect();
                }
            }
            sInstanceCount = 0;
        }
    }

    public synchronized static void unregisterAllInstances(boolean disconnect) {
        sInstanceCount = 0;
        if (sInstance != null) {
            sInstance.disconnect();
        }
    }

    public static MediaRouteSelector getMediaRouteSelector() {
        if (sInstance != null) {
            return sInstance.mMediaRouteSelector;
        }
        return null;
    }

    public static CastChannel getChannel() {
        if (sInstance != null) {
            return sInstance.mChannel;
        }
        return null;
    }

    public static GoogleApiClient getApiClient() {
        if (sInstance != null) {
            return sInstance.mApiClient;
        }
        return null;
    }

}
