package im.actor.sdk.core;

import android.content.Context;
import android.content.Intent;

import im.actor.core.AndroidMessenger;
import im.actor.core.Messenger;
import im.actor.core.webrtc.WebRTCController;
import im.actor.core.webrtc.WebRTCProvider;
import im.actor.sdk.core.webrtc.CallActivity;
import im.actor.sdk.core.webrtc.CallFragment;

public class AndroidWebRTCProvider implements WebRTCProvider {
    static WebRTCController controller;
    AndroidMessenger messenger;
    private long runningCallId;

    static CallFragment.CallCallback callCallback;

    @Override
    public void init(Messenger messenger, WebRTCController controller) {
        this.messenger = (AndroidMessenger) messenger;
        AndroidWebRTCProvider.controller = controller;
    }

    @Override
    public void onIncomingCall(long callId) {
        startCallActivity(callId, true);
    }

    @Override
    public void onOutgoingCall(long callId) {
        startCallActivity(callId, false);
    }

    @Override
    public void onOfferNeeded(long callId) {
        callCallback.onOfferNeeded();
    }

    @Override
    public void onAnswerReceived(long callId, String offerSDP) {
        callCallback.onAnswer(callId, offerSDP);
    }

    @Override
    public void onOfferReceived(long callId, String offerSDP) {
        callCallback.onOffer(callId, offerSDP);
    }

    @Override
    public void onCandidate(long callId, String id, int label, String sdp) {
        callCallback.onCandidate(callId, id, label, sdp);
    }

    @Override
    public void onCallEnd(long callId) {
        callCallback.onCallEnd();
    }

    public static void handleCall(CallFragment.CallCallback callback) {
        callCallback = callback;
    }

    public void startCallActivity(long callId, boolean incoming) {
        Context context = messenger.getContext();
        Intent callIntent = new Intent(context, CallActivity.class);
        callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        callIntent.putExtra("callId", callId);
        callIntent.putExtra("incoming", incoming);
        context.startActivity(callIntent);
        context.startActivity(callIntent);
    }

    public static WebRTCController getController() {
        return controller;
    }
}
