package im.actor.sdk.core.webrtc;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnection.IceGatheringState;
import org.webrtc.PeerConnection.SignalingState;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SessionDescription.Type;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;

import java.util.ArrayList;
import java.util.LinkedList;

import im.actor.core.webrtc.WebRTCController;
import im.actor.runtime.actors.Actor;
import im.actor.sdk.core.AndroidWebRTCProvider;

import static im.actor.sdk.util.ActorSDKMessenger.messenger;

public class WEBRTCActor extends Actor {
    public static final String TAG = "WEBRTC";
    public static final String TAG_REPORT = "WEBRTC_REPORT";

    private static final boolean REPORTING_ENABLED = false;
    private static boolean sInitializedAndroidGlobals;
    long callId;
    private static PeerConnectionFactory sFactory;
    volatile ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<PeerConnection.IceServer>();
    PeerConnectionObserver connectionObserver;
    private String remoteSdp;
    private boolean weAreOfferer = false;
    private MediaStream lMS;

    WebRTCController controller;

    StatsObserver stats = new StatsObserver() {
        @Override
        public void onComplete(StatsReport[] statsReports) {
            Log.d(TAG_REPORT, ">>>>>>>>Report>>>>>>>>");
            for (StatsReport r : statsReports) {
                Log.d(TAG_REPORT, r.toString());
            }
            Log.d(TAG_REPORT, "<<<<<<<<Report<<<<<<<<");
        }
    };

    public WEBRTCActor(WebRTCController controller, Context context) {

        this.controller = controller;

        iceServers.add(new PeerConnection.IceServer("stun:62.4.22.219:3478"));
        iceServers.add(new PeerConnection.IceServer("turn:62.4.22.219:3478?transport=tcp", "actor", "password"));
        iceServers.add(new PeerConnection.IceServer("turn:62.4.22.219:3478?transport=udp", "actor", "password"));

        if (!sInitializedAndroidGlobals) {
            sInitializedAndroidGlobals = true;
            PeerConnectionFactory.initializeAndroidGlobals(
                    context,  // Context
                    true,  // Audio Enabled
                    true,  // Video Enabled
                    true);  // Hardware Acceleration Enabled
//                            null); // Render EGL Context

        }

        if (sFactory == null) {
            sFactory = new PeerConnectionFactory();
        }
    }




    private void webrtcStats() {
        connectionObserver.getSdpObserver().pc.getStats(stats, null);
    }




    public void onCandidate(IceCandidate candidate) {
        Log.d(TAG, "<- candidate");
        connectionObserver.getSdpObserver().pc.addIceCandidate(candidate);
        Log.d(TAG, "add candidate:" + candidate.toString());
    }


    public void onAnswer(final String answer) {
        Log.d(TAG, "onAnswer");
        if (weAreOfferer) {
            setAnswer(answer);
        } else {
            Log.wtf(TAG, "received answer, but we answerer");
        }
    }

    private void setAnswer(final String sdp) {

        final SessionDescription finalRemoteSdp = new SessionDescription(Type.ANSWER,
                sdp);
        connectionObserver.getSdpObserver().pc.setRemoteDescription(
                connectionObserver.getSdpObserver(), finalRemoteSdp);
        Log.d(TAG, "setResmote answer: " + sdp);

    }

    public void onOffer(String offer) {
        Log.d(TAG, "onOffer");
        if (!weAreOfferer) {
            remoteSdp = offer;
            connectionObserver = createObserver(false);
            startReporting();
        } else {
            Log.wtf(TAG, "received offer, but we are offerer");
        }
    }



    public void onOfferNeeded(long callId) {
        this.callId = callId;
        Log.d(TAG, "doCall");
        connectionObserver = createObserver(true);
        startReporting();

    }

    private void startReporting() {
        if (REPORTING_ENABLED) {
            //Stats
            new Thread(new Runnable() {

                @Override
                public void run() {
                    while (connectionObserver != null && connectionObserver.getSdpObserver() != null && connectionObserver.getSdpObserver().pc != null) {
                        webrtcStats();
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }).start();
        }
    }

    private PeerConnectionObserver createObserver(boolean weAreOfferer) {
        Log.d(TAG, "createObserver: weAreOfferer - " + weAreOfferer);

        lMS = sFactory.createLocalMediaStream("ARDAMS");


        AudioSource audioSource = sFactory.createAudioSource(new MediaConstraints());
        lMS.addTrack(sFactory.createAudioTrack("ARDAMSa0", audioSource));

        MediaConstraints pcConstraints = makePcConstraints();
        PeerConnection pc = null;
        final PeerConnectionObserver observer = new PeerConnectionObserver(new PeerConnectionCreateObserver(pc, pcConstraints,
                weAreOfferer));
        pc = sFactory.createPeerConnection(iceServers,
                pcConstraints, observer);

        observer.getSdpObserver().setPc(pc);
        boolean localStreamAdded = pc.addStream(lMS);
        Log.d(TAG, "localStreamAdded - " + localStreamAdded);


        if (weAreOfferer) {
//            pc.createOffer(observer.getSdpObserver(), getMediaConstraints());
//            Log.d(TAG, "creating offer");

        } else {
            Log.d(TAG, "setResmote: " + remoteSdp);
            pc.setRemoteDescription(observer.getSdpObserver(), new SessionDescription(Type.OFFER, remoteSdp));
        }

        observer.getSdpObserver().iceReady();

        return observer;
    }



    /**
     * stop all streams from being cast to the server
     */
    private void unpublish() {
        if (connectionObserver == null) {
            return;
        }
        if(lMS!=null){
            connectionObserver.getSdpObserver().pc.removeStream(lMS);
            lMS.dispose();
        }

        destroy(connectionObserver.getSdpObserver().pc);


        lMS = null;


    }




    private void destroy(final PeerConnection pc) {
        if (pc != null) {
            pc.close();
            pc.dispose();
        }

    }

    public MediaConstraints makePcConstraints() {
        MediaConstraints pcConstraints = new MediaConstraints();
//
//        pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
//                "RtpDataChannels", "true"));
//        pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
//                "EnableDtlsSrtp", "false"));
//        pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
//                "DtlsSrtpKeyAgreement", "false"));
        return pcConstraints;
    }

    private class PeerConnectionCreateObserver implements SdpObserver {

        PeerConnection pc;
        MediaConstraints pcConstraints;
        SessionDescription localSdpWithIce;
        boolean mIceReady = false;

        PeerConnectionCreateObserver(PeerConnection pc, MediaConstraints pcConstraints, boolean publishing) {
            this.pc = pc;
            this.pcConstraints = pcConstraints;

        }

        public void setPc(PeerConnection pc) {
            this.pc = pc;
        }


        @Override
        public void onCreateFailure(String arg0) {
            Log.d(TAG, "SdpObserver#onCreateFailure: " + arg0);
        }


        @Override
        public void onCreateSuccess(SessionDescription sdp) {

            if (localSdpWithIce != null && weAreOfferer) {
                return;
            }


            if (mIceReady) {
                localSdpWithIce = sdp;
            }

            if (!weAreOfferer && localSdpWithIce != null) {
                controller.sendAnswer(localSdpWithIce.description);
                Log.d(TAG, "sending answer");
            }

            final SessionDescription finalSdp = sdp;
            if (pc != null) {

                pc.setLocalDescription(PeerConnectionCreateObserver.this,
                        finalSdp);
            } else {
                Log.wtf(TAG, "pc is null!!");
            }
        }

        @Override
        public void onSetFailure(String arg0) {
            Log.d(TAG, "SdpObserver#onSetFailure: " + arg0);
        }


        @Override
        public void onSetSuccess() {
            Log.d(TAG, "onSetSuccess");
            controller.readyForCandidates();

            if (weAreOfferer) {
                if (localSdpWithIce != null) {
                    controller.sendOffer(localSdpWithIce.description);
                    Log.d(TAG, "sending offer");
                }
            }


        }

        public void iceReady() {
            mIceReady = true;
            startConnecting();
        }

        private void startConnecting() {
            if (weAreOfferer) {
                pc.createOffer(this, pcConstraints);
                Log.d(TAG, "creating offer");
            } else {
                pc.createAnswer(this, pcConstraints);
                Log.d(TAG, "creating answer");
            }
        }

    }

    public class PeerConnectionObserver implements PeerConnection.Observer {
        /**
         * the associated sdp observer
         */
        private PeerConnectionCreateObserver mSdpObserver;

        /**
         * stream description
         */

        public PeerConnectionObserver(PeerConnectionCreateObserver observer) {
            mSdpObserver = observer;
        }

        public PeerConnectionCreateObserver getSdpObserver() {
            return mSdpObserver;
        }

        @Override
        public void onSignalingChange(SignalingState arg0) {
        }

        @Override
        public void onRemoveStream(MediaStream arg0) {
            // stream gone?
        }

        @Override
        public void onIceGatheringChange(IceGatheringState iceGatherState) {
            Log.d(TAG, "onIceGatheringChange: " + iceGatherState.name());
            if (iceGatherState == IceGatheringState.COMPLETE) {
//                mSdpObserver.iceReady();
            }
        }

        @Override
        public void onIceConnectionChange(IceConnectionState arg0) {
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        int candidatesCount = 0;

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            getSdpObserver().pc.addIceCandidate(iceCandidate);

            controller.sendCandidate(iceCandidate.sdpMLineIndex, iceCandidate.sdpMid, iceCandidate.sdp);
            Log.d(TAG, "candidate ->");

            if (++candidatesCount == 1) {
//                mSdpObserver.iceReady();
            }
        }

        @Override
        public void onDataChannel(DataChannel arg0) {
        }

        @Override
        public void onAddStream(final MediaStream media) {
        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(TAG, "PeerConnectionObserver.onRenegotiationNeeded");
        }
    }

    @Override
    public void onReceive(Object message) {
        if(message instanceof OfferNeeded){
            weAreOfferer = true;
            onOfferNeeded(((OfferNeeded) message).getCallId());
        }else if(message instanceof OfferReceived){
            weAreOfferer = false;
            onOffer(((OfferReceived) message).getOfferSDP());
        }else if(message instanceof Candidate){
            onCandidate(new IceCandidate(((Candidate) message).getId(), ((Candidate) message).getLabel(), ((Candidate) message).getSdp()));
        }else if(message instanceof AnswerReceived){
            onAnswer(((AnswerReceived) message).getOfferSDP());
        }else if(message instanceof EndCall){
            unpublish();
        }
    }

    public static class OfferNeeded{
        long callId;

        public OfferNeeded(long callId) {
            this.callId = callId;
        }

        public long getCallId() {
            return callId;
        }
    }

    public static class OfferReceived{
        long callId;
        String offerSDP;

        public OfferReceived(long callId, String offerSDP) {
            this.callId = callId;
            this.offerSDP = offerSDP;
        }

        public long getCallId() {
            return callId;
        }

        public String getOfferSDP() {
            return offerSDP;
        }
    }

    public static class AnswerReceived{
        long callId;
        String offerSDP;

        public AnswerReceived(long callId, String offerSDP) {
            this.callId = callId;
            this.offerSDP = offerSDP;
        }

        public long getCallId() {
            return callId;
        }

        public String getOfferSDP() {
            return offerSDP;
        }
    }

    public static class Candidate{
        long callId;
        String id;
        int label;
        String sdp;

        public Candidate(long callId, String id, int label, String sdp) {
            this.callId = callId;
            this.id = id;
            this.label = label;
            this.sdp = sdp;
        }

        public long getCallId() {
            return callId;
        }

        public String getId() {
            return id;
        }

        public int getLabel() {
            return label;
        }

        public String getSdp() {
            return sdp;
        }
    }

    public static class EndCall{
        long callId;

        public EndCall(long callId) {
            this.callId = callId;
        }

        public long getCallId() {
            return callId;
        }
    }

}
