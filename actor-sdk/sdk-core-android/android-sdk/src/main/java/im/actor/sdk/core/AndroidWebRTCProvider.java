package im.actor.sdk.core;

import android.content.Context;
import android.content.Intent;

import im.actor.core.AndroidMessenger;
import im.actor.core.Messenger;
import im.actor.core.webrtc.WebRTCController;
import im.actor.core.webrtc.WebRTCProvider;
import im.actor.runtime.actors.ActorCreator;
import im.actor.runtime.actors.ActorRef;
import im.actor.runtime.actors.ActorSystem;
import im.actor.runtime.actors.Props;
import im.actor.sdk.controllers.calls.CallActivity;
import im.actor.sdk.core.webrtc.WEBRTCActor;

public class AndroidWebRTCProvider implements WebRTCProvider {
    static WebRTCController controller;
    AndroidMessenger messenger;
    private long runningCallId;
    static ActorRef webrtcActor;

    @Override
    public void init(Messenger messenger, final WebRTCController controller) {
        this.messenger = (AndroidMessenger) messenger;
        this.controller = controller;

    }

    @Override
    public void onIncomingCall(long callId) {
        runningCallId = callId;
        startCallActivity(callId, true);
    }

    @Override
    public void onOutgoingCall(long callId) {
        runningCallId = callId;
        startCallActivity(callId, false);
    }

    @Override
    public void onOfferNeeded(long callId) {
        webrtcActor.send(new WEBRTCActor.OfferNeeded(callId));
    }

    @Override
    public void onAnswerReceived(long callId, String offerSDP) {
        webrtcActor.send(new WEBRTCActor.AnswerReceived(callId, offerSDP));
    }

    @Override
    public void onOfferReceived(long callId, String offerSDP) {
        webrtcActor.send(new WEBRTCActor.OfferReceived(callId, offerSDP));
    }

    @Override
    public void onCandidate(long callId, String id, int label, String sdp) {
        webrtcActor.send(new WEBRTCActor.Candidate(callId, id, label, sdp));
    }

    @Override
    public void onCallEnd(long callId) {
        webrtcActor.send(new WEBRTCActor.EndCall(callId));
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

    public static void initUsingActivity(final Context context){
        if(webrtcActor==null){
            ActorSystem.system().addDispatcher("android_calls", true);
            webrtcActor = ActorSystem.system().actorOf(Props.create(new ActorCreator() {
                @Override
                public WEBRTCActor create() {
                    return new WEBRTCActor(controller, context);
                }
            }).changeDispatcher("android_calls"), "actor/webrtc");
        }
    }

}
