package com.pixplicity.castdemo;

/*
 * Copyright (C) 2014 Google Inc. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.media.MediaRouter.RouteInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

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

/**
 * Main activity to send messages to the receiver.
 */
public class MainActivity extends ActionBarActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int REQUEST_SPEECH_RECOGNITION = 101;

    private MediaRouter mMediaRouter;
    private MediaRouteSelector mMediaRouteSelector;
    private MediaRouter.Callback mMediaRouterCallback;

    private GoogleApiClient mApiClient;
    private Cast.Listener mCastListener;
    private ConnectionCallbacks mConnectionCallbacks;
    private ConnectionFailedListener mConnectionFailedListener;

    private HelloWorldChannel mHelloWorldChannel;
    private boolean mApplicationStarted;
    private boolean mWaitingForReconnect;
    private String mSessionId;

    private final boolean mSingleUserMode = false;

    private EditText mEtMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setBackgroundDrawable(new ColorDrawable(android.R.color.transparent));

        // When the user clicks on the button, use Android voice recognition to get text
        Button voiceButton = (Button) findViewById(R.id.voiceButton);
        voiceButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                startVoiceRecognitionActivity();
            }
        });

        mEtMessage = (EditText) findViewById(R.id.et_message);
        ImageButton textButton = (ImageButton) findViewById(R.id.textButton);
        textButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                sendTextMessage(mEtMessage);
            }
        });
        mEtMessage.setOnEditorActionListener(new OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId,
                    KeyEvent event) {
                switch (actionId) {
                case EditorInfo.IME_ACTION_SEND:
                    sendTextMessage(mEtMessage);
                    return true;
                }
                return false;
            }
        });

        // Configure Cast device discovery
        mMediaRouter = MediaRouter.getInstance(getApplicationContext());
        mMediaRouteSelector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(getResources().getString(R.string.app_id)))
                .build();
        mMediaRouterCallback = new MyMediaRouterCallback();
    }

    /**
     * Android voice recognition
     */
    private void startVoiceRecognitionActivity() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.message_to_cast));
        startActivityForResult(intent, REQUEST_SPEECH_RECOGNITION);
    }

    /*
     * Handle the voice recognition response
     * 
     * @see android.support.v4.app.FragmentActivity#onActivityResult(int, int,
     * android.content.Intent)
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_SPEECH_RECOGNITION && resultCode == RESULT_OK) {
            ArrayList<String> matches = data
                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches.size() > 0) {
                String message = matches.get(0);
                Log.d(TAG, message);
                if (!sendMessage(message)) {
                    mEtMessage.setText(message);
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Start media router discovery
        mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
    }

    @Override
    protected void onPause() {
        if (isFinishing()) {
            // End media router discovery
            mMediaRouter.removeCallback(mMediaRouterCallback);
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItem mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item);
        MediaRouteActionProvider mediaRouteActionProvider = (MediaRouteActionProvider) MenuItemCompat
                .getActionProvider(mediaRouteMenuItem);
        // Set the MediaRouteActionProvider selector for device discovery.
        mediaRouteActionProvider.setRouteSelector(mMediaRouteSelector);
        return true;
    }

    /**
     * Callback for MediaRouter events
     */
    private class MyMediaRouterCallback extends MediaRouter.Callback {

        @Override
        public void onRouteSelected(MediaRouter router, RouteInfo info) {
            Log.d(TAG, "onRouteSelected");
            // Handle the user route selection.
            CastDevice device = CastDevice.getFromBundle(info.getExtras());
            // Launch the receiver app
            connect(device);
        }

        @Override
        public void onRouteUnselected(MediaRouter router, RouteInfo info) {
            Log.d(TAG, "onRouteUnselected: info=" + info);
            disconnect();
        }

    }

    /**
     * Connect to a device
     * 
     * @param device
     *            Device to launch onto
     */
    private void connect(CastDevice device) {
        try {
            mCastListener = new Cast.Listener() {

                @Override
                public void onApplicationDisconnected(int errorCode) {
                    Log.d(TAG, "application has stopped");
                    disconnect();
                }

            };
            // Connect to Google Play services
            mConnectionCallbacks = new ConnectionCallbacks();
            mConnectionFailedListener = new ConnectionFailedListener();
            Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions
                    .builder(device, mCastListener);
            mApiClient = new GoogleApiClient.Builder(this)
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
     * Google Play services callbacks
     */
    private class ConnectionCallbacks implements
            GoogleApiClient.ConnectionCallbacks {

        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG, "onConnected");

            if (mApiClient == null) {
                // We got disconnected while this runnable was pending
                // execution.
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
                result = Cast.CastApi.joinApplication(mApiClient, getString(R.string.app_id), mSessionId);
            } else {
                result = Cast.CastApi.launchApplication(mApiClient, getString(R.string.app_id), false);
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

                        mApplicationStarted = true;

                        // Create the custom message channel
                        mHelloWorldChannel = new HelloWorldChannel();
                        createMessageChannel();

                        // set the initial instructions
                        // on the receiver
                        sendMessage(null);
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
                        mHelloWorldChannel.getNamespace(),
                        mHelloWorldChannel);
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

    /**
     * Disconnect from the receiver
     */
    private void disconnect() {
        Log.d(TAG, "disconnecting");
        if (mApiClient != null) {
            if (mApplicationStarted) {
                if (mApiClient.isConnected()) {
                    try {
                        if (mSingleUserMode) {
                            Cast.CastApi.stopApplication(mApiClient, mSessionId);
                        } else {
                            Cast.CastApi.leaveApplication(mApiClient);
                        }
                        if (mHelloWorldChannel != null) {
                            Cast.CastApi.removeMessageReceivedCallbacks(
                                    mApiClient,
                                    mHelloWorldChannel.getNamespace());
                            mHelloWorldChannel = null;
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Exception while removing channel", e);
                    }
                    mApiClient.disconnect();
                }
                mApplicationStarted = false;
            }
            mApiClient = null;
        }
        mWaitingForReconnect = false;
        mSessionId = null;
    }

    private void sendTextMessage(final EditText textField) {
        if (sendMessage(textField.getText().toString())) {
            textField.setText(null);
        }
    }

    /**
     * Send a text message to the receiver
     * 
     * @param message
     */
    private boolean sendMessage(String message) {
        JSONObject json = new JSONObject();
        try {
            if (message != null) {
                json.put("code", 2);
                json.put("msg", message);
            } else {
                json.put("code", 1);
            }
            json.put("name", getUsername());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        if (mApiClient == null) {
            Toast.makeText(MainActivity.this, R.string.failed_no_connection, Toast.LENGTH_SHORT)
                    .show();
        }
        else if (mHelloWorldChannel == null) {
            Toast.makeText(MainActivity.this, R.string.failed_no_channel, Toast.LENGTH_SHORT)
                    .show();
        } else {
            try {
                Cast.CastApi.sendMessage(mApiClient,
                        mHelloWorldChannel.getNamespace(), json.toString())
                        .setResultCallback(new ResultCallback<Status>() {

                            @Override
                            public void onResult(Status result) {
                                if (!result.isSuccess()) {
                                    Log.e(TAG, "Sending message failed");
                                }
                            }
                        });
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Exception while sending message", e);
                Toast.makeText(MainActivity.this, R.string.failed_unknown, Toast.LENGTH_SHORT)
                        .show();
            }
        }
        return false;
    }

    public String getUsername() {
        AccountManager manager = AccountManager.get(this);
        Account[] accounts = manager.getAccountsByType("com.google");
        List<String> possibleEmails = new LinkedList<String>();

        for (Account account : accounts) {
            possibleEmails.add(account.name);
        }

        if (!possibleEmails.isEmpty() && possibleEmails.get(0) != null) {
            String email = possibleEmails.get(0);
            String[] parts = email.split("@");
            if (parts.length > 0 && parts[0] != null) {
                return parts[0];
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * Custom message channel
     */
    class HelloWorldChannel implements MessageReceivedCallback {

        /**
         * @return custom namespace
         */
        public String getNamespace() {
            return getString(R.string.namespace);
        }

        /*
         * Receive message from the receiver app
         */
        @Override
        public void onMessageReceived(CastDevice castDevice, String namespace,
                String message) {
            Log.d(TAG, "onMessageReceived: " + message);
        }

    }

}