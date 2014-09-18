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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognizerIntent;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

/**
 * Main activity to send messages to the receiver.
 */
public class MainActivity extends ActionBarActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int REQUEST_SPEECH_RECOGNITION = 101;

    private EditText mEtMessage;

    private String mUsername;

    private final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CastProxy.init(getApplicationContext());

        ActionBar actionBar = getSupportActionBar();
        actionBar.setBackgroundDrawable(new ColorDrawable(android.R.color.transparent));

        // When the user clicks on the button, use Android voice recognition to get text
        ImageButton voiceButton = (ImageButton) findViewById(R.id.voiceButton);
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
        CastProxy.getChannel().setUsername(mUsername);
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

}