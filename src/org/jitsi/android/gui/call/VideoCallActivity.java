/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.android.gui.call;

import java.beans.*;
import java.util.*;

import android.content.*;
import android.graphics.Color;
import android.media.*;
import android.os.*;
import android.support.v4.app.DialogFragment;
import android.util.*;
import android.view.*;
import android.view.Menu; // Disambiguation
import android.view.MenuItem; // Disambiguation
import android.widget.*;

import org.jitsi.R;
import org.jitsi.android.*;
import org.jitsi.android.gui.call.notification.*;
import org.jitsi.android.gui.controller.*;
import org.jitsi.android.gui.fragment.*;
import org.jitsi.android.gui.util.*;
import org.jitsi.android.gui.widgets.*;
import org.jitsi.android.util.java.awt.*;
import org.jitsi.impl.neomedia.jmfext.media.protocol.mediarecorder.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.osgi.*;
import org.jitsi.util.event.*;

import net.java.sip.communicator.service.gui.call.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.service.protocol.media.*;
import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.call.*;
import net.java.sip.communicator.util.call.CallPeerAdapter;

/**
 * The <tt>VideoCallActivity</tt> corresponds the call screen.
 *
 * @author Yana Stamcheva
 * @author Pawel Domas
 */
public class VideoCallActivity
    extends OSGiActivity
    implements  CallPeerRenderer,
                CallRenderer,
                CallChangeListener,
                PropertyChangeListener,
                ZrtpInfoDialog.SasVerificationListener
{
    /**
     * The logger
     */
    private static final Logger logger =
            Logger.getLogger(VideoCallActivity.class);

    /**
     * Tag name for fragment that handles proximity sensor in order to turn
     * the screen on and off.
     */
    private static final String PROXIMITY_FRAGMENT_TAG="proximity";

    /**
     * Tag name that identifies video handler fragment.
     */
    private static final String VIDEO_FRAGMENT_TAG = "video";

    /**
     * The call peer adapter that gives us access to all call peer events.
     */
    private CallPeerAdapter callPeerAdapter;

    /**
     * The corresponding call.
     */
    private Call call;

    /**
     * Indicates if the call timer has been started.
     */
    private boolean isCallTimerStarted = false;

    /**
     * The start date time of the call.
     */
    private Date callStartDate;

    /**
     * A timer to count call duration.
     */
    private Timer callDurationTimer;

    /**
     * The {@link CallConference} instance depicted by this <tt>CallPanel</tt>.
     */
    private CallConference callConference;

    /**
     * Flag indicates if the shutdown Thread has been started
     */
    private volatile boolean finishing = false;

    /**
     * The call identifier managed by {@link CallManager}
     */
    private String callIdentifier;

    /**
     * The zrtp SAS verification toast controller.
     */
    private LegacyClickableToastCtrl sasToastController;

    /**
     * Called when the activity is starting. Initializes the corresponding
     * call interface.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     * previously being shut down then this Bundle contains the data it most
     * recently supplied in onSaveInstanceState(Bundle).
     * Note: Otherwise it is null.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.video_call);

        callDurationTimer = new Timer();

        this.callIdentifier
                = getIntent().getExtras()
                        .getString(CallManager.CALL_IDENTIFIER);

        call = CallManager.getActiveCall(callIdentifier);

        if(call == null)
            throw new IllegalArgumentException(
                    "There's no call with id: "+callIdentifier);

        callConference = call.getConference();

        initMicrophoneView();
        initHangupView();

        // Registers as the call state listener
        call.addCallChangeListener(this);

        View toastView = findViewById(R.id.clickable_toast);
        View.OnClickListener toastclickHandler
                = new View.OnClickListener()
                        {
                            public void onClick(View v)
                            {
                                showZrtpInfoDialog();
                                sasToastController.hideToast(true);
                            }
                        };

        if(Build.VERSION.SDK_INT >= 11)
        {
            sasToastController
                    = new ClickableToastController( toastView,
                                                    toastclickHandler );
        }
        else
        {
            sasToastController
                    = new LegacyClickableToastCtrl( toastView,
                                                    toastclickHandler );
        }

        if(savedInstanceState == null)
        {
            /**
             * Adds fragment that turns on and off the screen when proximity sensor
             * detects FAR/NEAR distance.
             */
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(new ProximitySensorFragment(), PROXIMITY_FRAGMENT_TAG)
                    .add(new VideoHandlerFragment(), VIDEO_FRAGMENT_TAG)
                    .commit();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState)
    {
        sasToastController.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState)
    {
        super.onRestoreInstanceState(savedInstanceState);
        sasToastController.onRestoreInstanceState(savedInstanceState);
    }

    /**
     * Called when an activity is destroyed.
     */
    @Override
    protected void onDestroy()
    {
        if(isCallTimerStarted())
        {
            stopCallTimer();
        }

        super.onDestroy();
    }

    /**
     * Initializes the hangup button view.
     */
    private void initHangupView()
    {
        ImageView hangupView = (ImageView) findViewById(R.id.callHangupButton);

        hangupView.setOnClickListener(new View.OnClickListener()
        {
            public void onClick(View v)
            {
                // Start the hang up Thread, Activity will be closed later 
                // on call ended event
                CallManager.hangupCall(call);
            }
        });
    }

    /**
     * Called on call ended event. Runs on separate thread to release the EDT
     * Thread and preview surface can be hidden effectively.
     */
    private void doFinishActivity()
    {
        if(finishing)
            return;
        
        finishing = true;
        
        new Thread(new Runnable() 
        {
            public void run() 
            {
                // Waits for camera to be stopped
                getVideoFragment().ensureCameraClosed();

                switchActivity(JitsiApplication.getHomeScreenActivityClass());
            }
        }).start();        
    }

    /**
     * Initializes the microphone button view.
     */
    private void initMicrophoneView()
    {
        final ImageView microphoneButton
            = (ImageView) findViewById(R.id.callMicrophoneButton);

        microphoneButton.setOnClickListener(new View.OnClickListener()
        {
            public void onClick(View v)
            {
                CallManager.setMute(call, !isMuted());
            }
        });
        microphoneButton.setOnLongClickListener(new View.OnLongClickListener()
        {
            public boolean onLongClick(View view)
            {
                DialogFragment newFragment
                        = VolumeControlDialog.createInputVolCtrlDialog();
                newFragment.show( getSupportFragmentManager(),
                                  "vol_ctrl_dialog" );
                return true;
            }
        });
    }

    /**
     * Returns <tt>true</tt> if call is currently muted.
     *
     * @return <tt>true</tt> if call is currently muted.
     */
    private boolean isMuted()
    {
        return CallManager.isMute(call);
    }

    private void updateMuteStatus()
    {
        runOnUiThread(
        new Runnable()
        {
            public void run()
            {
                doUpdateMuteStatus();
            }
        });
    }

    private void doUpdateMuteStatus()
    {
        final ImageView microphoneButton
                = (ImageView) findViewById(R.id.callMicrophoneButton);

        if (isMuted())
        {
            microphoneButton.setBackgroundColor(0x50000000);
            microphoneButton.setImageResource(
                    R.drawable.callmicrophonemute);
        }
        else
        {
            microphoneButton.setBackgroundColor(Color.TRANSPARENT);
            microphoneButton.setImageResource(
                    R.drawable.callmicrophone);
        }
    }

    /**
     * Fired when call volume control button is clicked.
     * @param v the call volume control <tt>View</tt>.
     */
    public void onCallVolumeClicked(View v)
    {
        // Create and show the dialog.
        DialogFragment newFragment
                = VolumeControlDialog.createOutputVolCtrlDialog();
        newFragment.show(getSupportFragmentManager(), "vol_ctrl_dialog");
    }

    /**
     * Fired when speakerphone button is clicked.
     * @param v the speakerphone button <tt>View</tt>.
     */
    public void onSpeakerphoneClicked(View v)
    {
        AudioManager audioManager = JitsiApplication.getAudioManager();
        audioManager.setSpeakerphoneOn(!audioManager.isSpeakerphoneOn());
        updateSpeakerphoneStatus();
    }

    /**
     * Updates speakerphone button status.
     */
    private void updateSpeakerphoneStatus()
    {
        final ImageView speakerPhoneButton
                = (ImageView) findViewById(R.id.speakerphoneButton);

        if (JitsiApplication.getAudioManager().isSpeakerphoneOn())
        {
            speakerPhoneButton.setBackgroundColor(0x50000000);
        }
        else
        {
            speakerPhoneButton.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        /**
         * The call to:
         * setVolumeControlStream(AudioManager.STREAM_VOICE_CALL)
         * doesn't work when notification was being played during this Activity
         * creation, so the buttons must be captured and the voice call level
         * will be manipulated programmatically.
         */
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        switch (keyCode)
        {
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (action == KeyEvent.ACTION_UP)
                {
                    ((AudioManager)getSystemService(Context.AUDIO_SERVICE))
                            .adjustStreamVolume(AudioManager.STREAM_VOICE_CALL,
                                                AudioManager.ADJUST_RAISE,
                                                AudioManager.FLAG_SHOW_UI);
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action == KeyEvent.ACTION_DOWN)
                {
                    ((AudioManager)getSystemService(Context.AUDIO_SERVICE))
                            .adjustStreamVolume(AudioManager.STREAM_VOICE_CALL,
                                                AudioManager.ADJUST_LOWER,
                                                AudioManager.FLAG_SHOW_UI);
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    /**
     * Reinitialize the <tt>Activity</tt> to reflect current call status.
     */
    @Override
    protected void onResume()
    {
        super.onResume();

        // Clears the in call notification
        if(CallNotificationManager.get().isNotificationRunning(callIdentifier))
        {
            CallNotificationManager.get()
                    .stopNotification(this, callIdentifier);

        }
        // Registers as the call state listener
        call.addCallChangeListener(this);

        // Checks if call peer has video component
        Iterator<? extends CallPeer> peers = call.getCallPeers();
        if(peers.hasNext())
        {
            CallPeer callPeer = peers.next();
            addCallPeerUI(callPeer);
        }
        else
        {
            logger.error("There aren't any peers in the call");
            finish();
            return;
        }

        doUpdateHoldStatus();
        doUpdateCallDuration();
        doUpdateMuteStatus();
        updateSpeakerphoneStatus();
        initSecurityStatus();
    }

    /**
     * Called when this <tt>Activity</tt> is paused(hidden).
     * Releases all listeners and leaves the in call notification if the call is
     * in progress.
     */
    @Override
    protected void onPause()
    {
        call.removeCallChangeListener(this);

        if(callPeerAdapter != null)
        {
            Iterator<? extends CallPeer> callPeerIter = call.getCallPeers();
            if (callPeerIter.hasNext())
            {
                removeCallPeerUI(callPeerIter.next());
            }
            callPeerAdapter.dispose();
            callPeerAdapter = null;
        }

        if(call.getCallState() != CallState.CALL_ENDED)
        {
            leaveNotification();
        }

        super.onPause();
    }

    /**
     * Leaves the in call notification.
     */
    private void leaveNotification()
    {
        if(Build.VERSION.SDK_INT < 11)
        {
            // TODO: fix in call notifications for sdk < 11
            logger.warn("In call notifications not supported prior SDK 11");
            return;
        }

        String inCallStr = getString(R.string.in_call_with);

        Iterator<? extends CallPeer> callPeers = call.getCallPeers();
        if(callPeers.hasNext())
        {
            inCallStr += " " + callPeers.next().getDisplayName();
        }

        CallNotificationManager.get().showCallNotification(this, callIdentifier);
    }

    /**
     * Sets the peer name.
     *
     * @param name the name of the call peer
     */
    public void setPeerName(final String name)
    {
        // ActionBar is not support prior 3.0
        if(Build.VERSION.SDK_INT < 11)
            return;

        runOnUiThread(new Runnable()
        {
            public void run()
            {
                ActionBarUtil.setTitle(VideoCallActivity.this,
                    getResources().getString(
                        R.string.service_gui_CALL_WITH) + ": ");
                ActionBarUtil.setSubtitle(VideoCallActivity.this, name);
            }
        });
    }

    /**
     * Sets the peer image.
     *
     * @param image the avatar of the call peer
     */
    public void setPeerImage(byte[] image)
    {

    }

    /**
     * Sets the peer state.
     *
     * @param oldState the old peer state
     * @param newState the new peer state
     * @param stateString the state of the call peer
     */
    public void setPeerState(CallPeerState oldState, CallPeerState newState,
        final String stateString)
    {
        runOnUiThread(new Runnable()
        {
            public void run()
            {
                TextView statusName = (TextView) findViewById(R.id.callStatus);

                statusName.setText(stateString);
            }
        });
    }

    /**
     * Updates the call duration string. Invoked on UI thread.
     */
    public void updateCallDuration()
    {
        runOnUiThread(new Runnable()
        {
            public void run()
            {
                doUpdateCallDuration();
            }
        });
    }

    /**
     * Updates the call duration string.
     */
    private void doUpdateCallDuration()
    {
        if(callStartDate == null)
            return;
        String timeStr = GuiUtils.formatTime(
                callStartDate.getTime(),
                System.currentTimeMillis());
        TextView callTime = (TextView) findViewById(R.id.callTime);
        callTime.setText(timeStr);
    }

    public void setErrorReason(String reason) {}

    public void setMute(boolean isMute)
    {
        // Just invoke mute UI refresh
        updateMuteStatus();
    }

    /**
     * Method mapped to hold button view on click event
     *
     * @param holdButtonView the button view that has been clicked
     */
    public void onHoldButtonClicked(View holdButtonView)
    {
        CallManager.putOnHold(call, !isOnHold());
    }

    private boolean isOnHold()
    {
        boolean onHold = false;
        Iterator<? extends CallPeer> peers = call.getCallPeers();
        if(peers.hasNext())
        {
            CallPeerState peerState = call.getCallPeers().next().getState();
            onHold = CallPeerState.ON_HOLD_LOCALLY.equals(peerState)
                    || CallPeerState.ON_HOLD_MUTUALLY.equals(peerState);
        }
        else 
        {
            logger.warn("No peer belongs to call: "+call.toString());    
        }

        return onHold;
    }

    public void setOnHold(boolean isOnHold){}

    /**
     * Updates on hold button to represent it's actual state
     */
    private void updateHoldStatus()
    {
        runOnUiThread(new Runnable()
        {
            public void run()
            {
                doUpdateHoldStatus();
            }
        });
    }

    /**
     * Updates on hold button to represent it's actual state.
     * Called from {@link #updateHoldStatus()}.
     */
    private void doUpdateHoldStatus()
    {
        final ImageView holdButton
                = (ImageView) findViewById(R.id.callHoldButton);

        if (isOnHold())
        {
            holdButton.setBackgroundColor(0x50000000);
        }
        else
        {
            holdButton.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    public void printDTMFTone(char dtmfChar)
    {

    }

    public CallRenderer getCallRenderer()
    {
        return this;
    }

    public void setLocalVideoVisible(final boolean isVisible)
    {
        // It can not be hidden here, because the preview surface will be
        // destroyed and camera recording system will crash     
    }

    private VideoHandlerFragment getVideoFragment()
    {
        return (VideoHandlerFragment)getSupportFragmentManager()
                .findFragmentByTag("video");
    }

    public boolean isLocalVideoVisible()
    {
        return getVideoFragment().isLocalVideoVisible();
    }

    public Call getCall()
    {
        return call;
    }

    public CallPeerRenderer getCallPeerRenderer(CallPeer callPeer)
    {
        return this;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.video_call_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId())
        {
            case R.id.low_resolution:
                return true;
            case R.id.high_resolution:
                return true;
            case R.id.call_info_item:
                showCallInfoDialog();
                return true;
            case R.id.call_zrtp_info_item:
                showZrtpInfoDialog();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Displays technical call information dialog.
     */
    private void showCallInfoDialog()
    {
        CallInfoDialogFragment callInfo
                = CallInfoDialogFragment.newInstance(
                getIntent().getStringExtra(
                        CallManager.CALL_IDENTIFIER));

        callInfo.show(getSupportFragmentManager(), "callinfo");
    }

    /**
     * Displays ZRTP call information dialog.
     */
    private void showZrtpInfoDialog()
    {
        ZrtpInfoDialog zrtpInfo
            = ZrtpInfoDialog.newInstance(
                getIntent().getStringExtra(CallManager.CALL_IDENTIFIER));

        zrtpInfo.show(getSupportFragmentManager(), "zrtpinfo");
    }

    public void propertyChange(PropertyChangeEvent evt)
    {
        /*
         * If a Call is added to or removed from the CallConference depicted
         * by this CallPanel, an update of the view from its model will most
         * likely be required.
         */
        if (evt.getPropertyName().equals(CallConference.CALLS))
            onCallConferenceEventObject(evt);
    }

    public void callPeerAdded(CallPeerEvent evt)
    {
        CallPeer callPeer = evt.getSourceCallPeer();

        addCallPeerUI(callPeer);

        onCallConferenceEventObject(evt);
    }

    public void callPeerRemoved(CallPeerEvent evt)
    {
        CallPeer callPeer = evt.getSourceCallPeer();

        if (callPeerAdapter != null)
        {
            callPeer.addCallPeerListener(callPeerAdapter);
            callPeer.addCallPeerSecurityListener(callPeerAdapter);
            callPeer.addPropertyChangeListener(callPeerAdapter);
        }

        setPeerState(callPeer.getState(),
                     callPeer.getState(),
                     callPeer.getState().getLocalizedStateString());

        onCallConferenceEventObject(evt);
    }

    public void callStateChanged(CallChangeEvent evt)
    {
        onCallConferenceEventObject(evt);
    }

    /**
     * Invoked by {@link #callConferenceListener} to notify this instance about
     * an <tt>EventObject</tt> related to the <tt>CallConference</tt> depicted
     * by this <tt>CallPanel</tt>, the <tt>Call</tt>s participating in it,
     * the <tt>CallPeer</tt>s associated with them, the
     * <tt>ConferenceMember</tt>s participating in any telephony conferences
     * organized by them, etc. In other words, notifies this instance about
     * any change which may cause an update to be required so that this view
     * i.e. <tt>CallPanel</tt> depicts the current state of its model i.e.
     * {@link #callConference}.
     *
     * @param ev the <tt>EventObject</tt> this instance is being notified
     * about.
     */
    private void onCallConferenceEventObject(EventObject ev)
    {
        /*
         * The main task is to invoke updateViewFromModel() in order to make
         * sure that this view depicts the current state of its model.
         */

        try
        {
            /*
             * However, we seem to be keeping track of the duration of the call
             * (i.e. the telephony conference) in the user interface. Stop the
             * Timer which ticks the duration of the call as soon as the
             * telephony conference depicted by this instance appears to have
             * ended. The situation will very likely occur when a Call is
             * removed from the telephony conference or a CallPeer is removed
             * from a Call.
             */
            boolean tryStopCallTimer = false;

            if (ev instanceof CallPeerEvent)
            {
                tryStopCallTimer
                    = (CallPeerEvent.CALL_PEER_REMOVED
                            == ((CallPeerEvent) ev).getEventID());
            }
            else if (ev instanceof PropertyChangeEvent)
            {
                PropertyChangeEvent pcev = (PropertyChangeEvent) ev;

                tryStopCallTimer
                    = (CallConference.CALLS.equals(pcev)
                            && (pcev.getOldValue() instanceof Call)
                            && (pcev.getNewValue() == null));
            }

            if (tryStopCallTimer
                    && (callConference.isEnded()
                            || callConference.getCallPeerCount() == 0))
            {
                stopCallTimer();
                doFinishActivity();
            }
        }
        finally
        {
            updateViewFromModel(ev);
        }
    }

    /**
     * Starts the timer that counts call duration.
     */
    public void startCallTimer()
    {
        if(callStartDate == null)
        {
            this.callStartDate = new Date();
        }

        this.callDurationTimer
            .schedule(new CallTimerTask(),
                new Date(System.currentTimeMillis()), 1000);

        this.isCallTimerStarted = true;
    }

    /**
     * Stops the timer that counts call duration.
     */
    public void stopCallTimer()
    {
        this.callDurationTimer.cancel();
    }

    /**
     * Returns <code>true</code> if the call timer has been started, otherwise
     * returns <code>false</code>.
     * @return <code>true</code> if the call timer has been started, otherwise
     * returns <code>false</code>
     */
    public boolean isCallTimerStarted()
    {
        return isCallTimerStarted;
    }

    /**
     * {@inheritDoc}
     */
    public void onSasVerificationChanged(boolean isVerified)
    {
        doUpdatePadlockStatus(true, isVerified);
    }

    /**
     * Each second refreshes the time label to show to the user the exact
     * duration of the call.
     */
    private class CallTimerTask
        extends TimerTask
    {
        @Override
        public void run()
        {
            updateCallDuration();
        }
    }

    private void addCallPeerUI(CallPeer callPeer)
    {
        callPeerAdapter
            = new CallPeerAdapter(callPeer, this);
        callPeer.addCallPeerListener(callPeerAdapter);
        callPeer.addCallPeerSecurityListener(callPeerAdapter);
        callPeer.addPropertyChangeListener(callPeerAdapter);

        setPeerState(   null,
                        callPeer.getState(),
                        callPeer.getState().getLocalizedStateString());
        setPeerName(callPeer.getDisplayName());

        CallPeerState currentState = callPeer.getState();
        if( (currentState == CallPeerState.CONNECTED
             || CallPeerState.isOnHold(currentState))
                 && !isCallTimerStarted())
        {
            callStartDate = new Date(callPeer.getCallDurationStartTime());
            startCallTimer();
        }
    }

    /**
     * Removes given <tt>callPeer</tt> from UI.
     *
     * @param callPeer the {@link CallPeer} to be removed from UI.
     */
    private void removeCallPeerUI(CallPeer callPeer)
    {
        callPeer.removeCallPeerListener(callPeerAdapter);
        callPeer.removeCallPeerSecurityListener(callPeerAdapter);
        callPeer.removePropertyChangeListener(callPeerAdapter);
    }

    private void updateViewFromModel(EventObject ev)
    {
    }

    public void updateHoldButtonState() 
    {
        updateHoldStatus();
    }

    public void dispose() {}

    public void securityNegotiationStarted(
        CallPeerSecurityNegotiationStartedEvent securityStartedEvent) {}

    /**
     * Initializes current security status displays.
     */
    private void initSecurityStatus()
    {
        boolean isSecure=false;
        boolean isVerified=false;
        ZrtpControl zrtpCtrl = null;

        Iterator<? extends CallPeer> callPeers = call.getCallPeers();
        if(callPeers.hasNext())
        {
            CallPeer cpCandidate = callPeers.next();
            if(cpCandidate instanceof MediaAwareCallPeer<?, ?, ?>)
            {
                MediaAwareCallPeer<?, ?, ?> mediaAwarePeer
                        = (MediaAwareCallPeer<?, ?, ?>) cpCandidate;
                SrtpControl srtpCtrl = mediaAwarePeer.getMediaHandler()
                        .getEncryptionMethod(MediaType.AUDIO);
                isSecure = srtpCtrl != null
                        && srtpCtrl.getSecureCommunicationStatus();

                if(srtpCtrl instanceof ZrtpControl)
                {
                    zrtpCtrl = (ZrtpControl)srtpCtrl;
                    isVerified = zrtpCtrl.isSecurityVerified();
                }
                else
                {
                    isVerified = true;
                }
            }
        }

        // Protocol name label
        ViewUtil.setTextViewValue(
                findViewById(R.id.videoCallLayout),
                R.id.security_protocol,
                zrtpCtrl != null ? "zrtp" : "");

        doUpdatePadlockStatus(isSecure, isVerified);
    }

    /**
     * Updates padlock status text, icon and it's background color.
     *
     * @param isSecure <tt>true</tt> if the call is secured.
     * @param isVerified <tt>true</tt> if zrtp SAS string is verified.
     */
    private void doUpdatePadlockStatus(boolean isSecure, boolean isVerified)
    {
        if(isSecure)
        {
            if(isVerified)
            {
                // Security on
                setPadlockColor(R.color.green_padlock);
                setPadlockSecure(true);
            }
            else
            {
                // Security pending
                setPadlockColor(R.color.orange_padlock);
                setPadlockSecure(true);
            }
        }
        else
        {
            // Security off
            setPadlockColor(R.color.red_padlock);
            setPadlockSecure(false);
        }
    }

    /**
     * Sets the security padlock background color.
     *
     * @param colorId the color resource id that will be used.
     */
    private void setPadlockColor(int colorId)
    {
        View padlockGroup = findViewById(R.id.security_group);
        int color = getResources().getColor(colorId);
        padlockGroup.setBackgroundColor(color);
    }

    /**
     * Updates padlock icon based on security status.
     *
     * @param isSecure <tt>true</tt> if the call is secure.
     */
    private void setPadlockSecure(boolean isSecure)
    {
        ViewUtil.setImageViewIcon(
                findViewById(R.id.videoCallLayout),
                R.id.security_padlock,
                isSecure ? R.drawable.secure_on : R.drawable.secure_off);
    }

    /**
     * {@inheritDoc}
     */
    public void securityPending()
    {
        runOnUiThread(new Runnable()
        {
            public void run()
            {
                doUpdatePadlockStatus(false, false);
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    public void securityTimeout(CallPeerSecurityTimeoutEvent evt)
    {

    }

    /**
     * {@inheritDoc}
     */
    public void setSecurityPanelVisible(boolean visible) {}

    /**
     * {@inheritDoc}
     */
    public void securityOff(CallPeerSecurityOffEvent evt)
    {
        runOnUiThread(new Runnable()
        {
            public void run()
            {
                doUpdatePadlockStatus(false, false);
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    public void securityOn(final CallPeerSecurityOnEvent evt)
    {
        runOnUiThread(new Runnable()
        {
            public void run()
            {
                SrtpControl srtpCtrl = evt.getSecurityController();
                ZrtpControl zrtpControl = null;
                if(srtpCtrl instanceof ZrtpControl)
                {
                    zrtpControl = (ZrtpControl) srtpCtrl;
                }

                boolean isVerified
                        = zrtpControl != null
                                && zrtpControl.isSecurityVerified();

                doUpdatePadlockStatus(true, isVerified);

                // Protocol name label
                ViewUtil.setTextViewValue(
                        findViewById(R.id.videoCallLayout),
                        R.id.security_protocol,
                        zrtpControl != null ? "zrtp" : "");

                if(!isVerified)
                {
                    String toastMsg
                        = getString(R.string.service_gui_security_VERIFY_TOAST);
                    sasToastController.showToast(false, toastMsg);
                }
            }
        });
    }

    /**
     * Creates new video call intent for given <tt>callIdentifier</tt>.
     *
     * @param parent the parent <tt>Context</tt> that will be used to start new
     * <tt>Activity</tt>.
     * @param callIdentifier the call ID managed by {@link CallManager}.
     *
     * @return new video call <tt>Intent</tt> parametrized with given
     * <tt>callIdentifier</tt>.
     */
    static public Intent createVideoCallIntent(Context parent,
                                              String callIdentifier)
    {
        Intent videoCallIntent
                = new Intent( parent,
                              VideoCallActivity.class);

        videoCallIntent.putExtra(
                CallManager.CALL_IDENTIFIER,
                callIdentifier);

        VideoHandlerFragment.wasVideoEnabled = false;

        return videoCallIntent;
    }
}