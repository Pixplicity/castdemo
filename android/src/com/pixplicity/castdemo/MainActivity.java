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

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognizerIntent;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

/**
 * Main activity to send messages to the receiver.
 */
public class MainActivity extends ActionBarActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int REQUEST_SPEECH_RECOGNITION = 101;

    private EditText mEtMessage;
    private ImageButton mBtSend;
    private ImageButton mBtSpeak;
    private ListView mLvMessages;
    private ViewGroup mVgLogo;

    private String mUsername;

    private ArrayList<String> mMessageList = new ArrayList<String>();
    private ArrayAdapter<String> mMessageAdapter;

    private final Handler mHandler = new Handler();

    /**
     * Custom message channel
     */
    public class HelloWorldChannel implements CastProxy.CastChannel {

        public final Context mApplicationContext;

        public HelloWorldChannel(Context applicationContext) {
            mApplicationContext = applicationContext;
        }

        /**
         * @return Custom namespace over which the channel is communicated
         */
        @Override
        public String getNamespace() {
            return mApplicationContext.getString(R.string.namespace);
        }

        @Override
        public void onConnected() {
            Log.d(TAG, "onConnected");
            // Set the initial instructions on the receiver
            sendMessage(null);
            setConnected(true);
        }

        @Override
        public void onReconnected() {
            Log.d(TAG, "onReconnected");
            setConnected(true);
        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "onDisconnected");
            setConnected(false);
        }

        @Override
        public void onMessageReceived(CastDevice castDevice, String namespace, String message) {
            Log.d(TAG, "onMessageReceived: " + message);
            try {
                JSONObject json = new JSONObject(message);
                String name = json.getString("name");
                String msg = json.getString("msg");
                mMessageList.add(msg);
                updateMessages();
                mMessageAdapter.notifyDataSetChanged();
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Send a text message to the receiver
         * 
         * @param message
         */
        @Override
        public boolean sendMessage(String message) {
            JSONObject json = new JSONObject();
            try {
                if (message != null) {
                    if (message.length() == 0) {
                        return false;
                    }
                    json.put("code", 2);
                    json.put("msg", message);
                } else {
                    json.put("code", 1);
                }
                json.put("name", getUsername());
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            GoogleApiClient apiClient = CastProxy.getApiClient();
            if (apiClient == null) {
                Toast.makeText(mApplicationContext, R.string.failed_no_connection, Toast.LENGTH_SHORT)
                        .show();
            } else {
                try {
                    Log.d(TAG, "sending: " + message);
                    Cast.CastApi.sendMessage(apiClient,
                            getNamespace(), json.toString())
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
                    Toast.makeText(mApplicationContext, R.string.failed_unknown, Toast.LENGTH_SHORT)
                            .show();
                }
            }
            return false;
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CastProxy.init(getApplicationContext(), new HelloWorldChannel(getApplicationContext()));

        ActionBar actionBar = getSupportActionBar();
        actionBar.setBackgroundDrawable(new ColorDrawable(android.R.color.transparent));

        // Sends the message over the channel
        mBtSend = (ImageButton) findViewById(R.id.bt_send);
        mBtSend.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                sendTextMessage(mEtMessage);
            }
        });

        // When the user clicks on the "speak" button, use Android voice recognition to get text
        mBtSpeak = (ImageButton) findViewById(R.id.bt_speak);
        mBtSpeak.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                startVoiceRecognitionActivity();
            }
        });

        mEtMessage = (EditText) findViewById(R.id.et_message);
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

        mLvMessages = (ListView) findViewById(R.id.lv_messages);

        mVgLogo = (ViewGroup) findViewById(R.id.vg_logo);

        setConnected(false);
    }

    private void setConnected(boolean connected) {
        mBtSend.setEnabled(connected);
        mBtSpeak.setEnabled(connected);
        mEtMessage.setEnabled(connected);
        if (connected) {
            mEtMessage.requestFocus();
            // Implicity request a soft input mode
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(mEtMessage, InputMethodManager.SHOW_IMPLICIT);
        }

    }

    private void updateMessages() {
        if (mMessageList.size() > 0) {
            mVgLogo.setVisibility(View.INVISIBLE);
        }
        if (mMessageAdapter == null) {
            mMessageAdapter = new ArrayAdapter<String>(MainActivity.this,
                    android.R.layout.simple_list_item_2, mMessageList);
            mLvMessages.setAdapter(mMessageAdapter);
        }
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
                if (!CastProxy.getChannel().sendMessage(message)) {
                    mEtMessage.setText(message);
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onStart() {
        super.onStart();
        CastProxy.registerInstance();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList("messages", mMessageList);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mLvMessages.setAdapter(null);
        mMessageList = savedInstanceState.getStringArrayList("messages");
        updateMessages();
    }

    @Override
    protected void onStop() {
        // Unregister with a delay to cope with orientation changes
        mHandler.postDelayed(new Runnable() {

            @Override
            public void run() {
                CastProxy.unregisterInstance(true);
            }
        }, 2000);
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItem mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item);
        MediaRouteActionProvider mediaRouteActionProvider = (MediaRouteActionProvider) MenuItemCompat
                .getActionProvider(mediaRouteMenuItem);
        // Set the MediaRouteActionProvider selector for device discovery.
        mediaRouteActionProvider.setRouteSelector(CastProxy.getMediaRouteSelector());
        return true;
    }

    private void sendTextMessage(final EditText textField) {
        if (CastProxy.getChannel().sendMessage(textField.getText().toString())) {
            textField.setText(null);
        }
    }

    public String getUsername() {
        if (mUsername == null) {
            mUsername = "anonymous";
            // Default username
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
                    mUsername = parts[0];
                }
            }
        }
        return mUsername;
    }

}