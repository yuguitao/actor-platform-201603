package im.actor.sdk.core.webrtc;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
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
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRenderer.I420Frame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;

import im.actor.runtime.json.JSONArray;
import im.actor.runtime.json.JSONException;
import im.actor.runtime.json.JSONObject;
import im.actor.sdk.core.AndroidWebRTCProvider;

public class WEBRTCConnector {
    public static final String TAG = "WEBRTC";
    public static final String TAG_REPORT = "WEBRTC_REPORT";

    private static final boolean REPORTING_ENABLED = false;
    private static final boolean LIBJINGLE_LOGS = false;

    private static final boolean ENABLE_OUR_TURN = true;
    private static final boolean ENABLE_GIST_STUNS_TURNS = false;
    /**
     * flag to store if basic initialization has happened
     */
    private static boolean sInitializedAndroidGlobals;
    /**
     * current call id
     */
    long callId;
    /**
     * local video stream
     */
    private VideoSource mVideoSource;
    /**
     * local video capturer
     */
    private VideoCapturer mVideoCapturer;
    /**
     * if local video stream was paused
     */
    private boolean mVideoStopped = false;
    /**
     * factory for peer connections
     */
    private static PeerConnectionFactory sFactory;
    /**
     * list of stun and turn servers available for all connections
     */
    volatile ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<PeerConnection.IceServer>();
    /**
     * the handler for the special video chat thread
     */
    private static Handler sVcHandler = null;
    /**
     * special lock object when accessing the vc handler instance
     */
    private static Object sVcLock = new Object();
    /**
     * server confirmed rights
     */
    public static final boolean VIDEO_ENABLED = false;

    MyPcObserver connectionObserver;
    private boolean unpublishing = false;


    private boolean haveOffer;
    private boolean allowAnswer;
    private String remoteSdp;
    private boolean weAreOfferer = false;
    private LinkedList<IceCandidate> queuedRemoteCandidates =
            new LinkedList<IceCandidate>();
    private boolean inited = false;
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

    private boolean offerNeeded = false;
    private boolean offerReady = false;
    private boolean offerSent = false;
    private String offer;

    public void onOfferNeeded() {
        offerNeeded = true;
        checkSendOffer();
    }

    private void checkSendOffer(){
        if(offerNeeded && offerReady && !offerSent){
            offerSent = true;
            AndroidWebRTCProvider.getController().sendOffer(offer);
        }
    }


    /**
     * peer connection observer
     */
    public class MyPcObserver implements PeerConnection.Observer {
        /**
         * the associated sdp observer
         */
        private WebRtcObserver mSdpObserver;

        /**
         * stream description
         */

        public MyPcObserver(WebRtcObserver observer) {
            mSdpObserver = observer;
        }

        public WebRtcObserver getSdpObserver() {
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
            if (queuedRemoteCandidates != null) {
                queuedRemoteCandidates.add(iceCandidate);
            } else {
                getSdpObserver().pc.addIceCandidate(iceCandidate);
            }
            AndroidWebRTCProvider.getController().sendCandidate(iceCandidate.sdpMLineIndex, iceCandidate.sdpMid, iceCandidate.sdp);
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
            context.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    callFragment.onConnected();
                }
            });

        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(TAG, "PeerConnectionObserver.onRenegotiationNeeded");
        }
    }

    private void webrtcStats() {
        connectionObserver.getSdpObserver().pc.getStats(stats, null);
    }

    Activity context;
    CallFragment callFragment;


    /**
     * local media stream
     */
    private MediaStream lMS;

    public WEBRTCConnector(final Activity context, CallFragment fragment) {
        this.context = context;
        this.callFragment = fragment;

    }


    public void init(JSONObject ice) {

        if (LIBJINGLE_LOGS) {
            Logging.enableTracing("logcat:",
                    EnumSet.of(Logging.TraceLevel.TRACE_ALL),
                    Logging.Severity.LS_SENSITIVE);
        }

        iceServers.add(new PeerConnection.IceServer("stun:62.4.22.219:3478"));
        iceServers.add(new PeerConnection.IceServer("turn:62.4.22.219:3478?transport=tcp", "actor", "password"));
        iceServers.add(new PeerConnection.IceServer("turn:62.4.22.219:3478?transport=udp", "actor", "password"));



        synchronized (sVcLock) {
            if (sVcHandler == null) {
                HandlerThread vcthread = new HandlerThread(
                        "LicodeConnectorThread");
                vcthread.start();
                sVcHandler = new Handler(vcthread.getLooper());
            }
        }
        if (context == null) {
            throw new NullPointerException(
                    "Failed to initialize WEBRTCConnector. Activity is required.");
        }

        Runnable init = new Runnable() {
            @Override
            public void run() {
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

            ;
        };
        sVcHandler.post(init);
        inited = true;
        callFragment.onConnectorInited();
    }


    public void onCandidate(IceCandidate candidate) {
        Log.d(TAG, "<- candidate");

        if (queuedRemoteCandidates != null) {
            queuedRemoteCandidates.add(candidate);
        } else {
            connectionObserver.getSdpObserver().pc.addIceCandidate(candidate);
            Log.d(TAG, "add candidate:" + candidate.toString());
        }
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
        context.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectionObserver.getSdpObserver().pc.setRemoteDescription(
                        connectionObserver.getSdpObserver(), finalRemoteSdp);
                Log.d(TAG, "setResmote answer: " + sdp);


            }
        });

    }

    public void onOffer(String offer) {
        Log.d(TAG, "onOffer");
        if (!weAreOfferer) {
            haveOffer = true;
            this.remoteSdp = offer;
            if (allowAnswer) {
                answer(callId);
            }
        } else {
            Log.wtf(TAG, "received offer, but we are offerer");
        }
    }

    /**
     * get access to the camera
     */
    private VideoCapturer getVideoCapturer() {
        String[] cameraFacing = {"front", "back"};
        int[] cameraIndex = {0, 1};
        int[] cameraOrientation = {0, 90, 180, 270};
        for (String facing : cameraFacing) {
            for (int index : cameraIndex) {
                for (int orientation : cameraOrientation) {
                    String name = "Camera " + index + ", Facing " + facing
                            + ", Orientation " + orientation;
                    VideoCapturer capturer = VideoCapturer.create(name);
                    if (capturer != null) {
                        Log.d(TAG, "Using camera: " + name);
                        return capturer;
                    }
                }
            }
        }
        throw new RuntimeException("Failed to open capturer");
    }



    private class WebRtcObserver implements SdpObserver {

        PeerConnection pc;
        MediaConstraints pcConstraints;
        SessionDescription localSdpWithIce;
        boolean mIceReady = false;

        WebRtcObserver(PeerConnection pc, MediaConstraints pcConstraints, boolean publishing) {
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
                AndroidWebRTCProvider.getController().sendAnswer(localSdpWithIce.description);
                Log.d(TAG, "sending answer");
            }

//            if (sdp.type == Type.OFFER) {
//                messenger().sendCallSignal(callId, new OfferSignal(sdp.description).toByteArray());
//            }
//
//            if (sdp.type == Type.ANSWER || sdp.type == Type.PRANSWER) {
//                messenger().sendCallSignal(callId, new AnswerSignal(sdp.description).toByteArray());
//            }


            final SessionDescription finalSdp = sdp;
            if (pc != null) {

                context.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pc.setLocalDescription(WebRtcObserver.this,
                                finalSdp);
                    }
                });

            } else {
                Log.wtf(TAG, "pc is null!!");
            }
        }

        @Override
        public void onSetFailure(String arg0) {
            Log.d(TAG, "SdpObserver#onSetFailure: " + arg0);
        }

        boolean offerSended = false;

        @Override
        public void onSetSuccess() {
            Log.d(TAG, "onSetSuccess");
            context.runOnUiThread(new Runnable() {
                public void run() {
                    if (pc.getRemoteDescription() != null && queuedRemoteCandidates != null) {
                        for (IceCandidate candidate : queuedRemoteCandidates) {
                            pc.addIceCandidate(candidate);
                            Log.d(TAG, "add candidate:" + candidate.toString());
                        }
                        queuedRemoteCandidates = null;
                    }
                }
            });

            if (weAreOfferer) {
                if (!offerSended && localSdpWithIce != null) {
                    offerSended = true;
                    offer = localSdpWithIce.description;
                    offerReady = true;
                    checkSendOffer();
                    Log.d(TAG, "sending offer");

                }
            } else {
//                pc.createAnswer(this, getMediaConstraints());
//                Log.d(TAG, "creating answer");

            }


        }

        boolean first = true;

        public void iceReady() {
            if (first) {
                first = false;
                mIceReady = true;
                startConnecting();
            }
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

    private boolean once = true;

    public void call(long callId) {
        if (once) {
            once = false;
            weAreOfferer = true;
            this.callId = callId;
            sVcHandler.post(new Runnable() {
                @Override
                public void run() {
                    doCall();
                }
            });
        }

    }

    /**
     * begin streaming to server - MUST run on VcThread
     */
    private void doCall() {
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
                    while (connectionObserver != null && connectionObserver.getSdpObserver() != null && connectionObserver.getSdpObserver().pc != null && !unpublishing) {
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

    private MyPcObserver createObserver(boolean weAreOfferer) {
        Log.d(TAG, "createObserver: weAreOfferer - " + weAreOfferer);

        lMS = sFactory.createLocalMediaStream("ARDAMS");

        if (VIDEO_ENABLED) {
            MediaConstraints videoConstraints = new MediaConstraints();

            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    "maxWidth", "320"));
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    "maxHeight", "240"));
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    "maxFrameRate", "10"));
            mVideoCapturer = getVideoCapturer();
            mVideoSource = sFactory.createVideoSource(mVideoCapturer,
                    videoConstraints);
            VideoTrack videoTrack = sFactory.createVideoTrack("ARDAMSv0",
                    mVideoSource);
            lMS.addTrack(videoTrack);

        }

        AudioSource audioSource = sFactory.createAudioSource(new MediaConstraints());
        lMS.addTrack(sFactory.createAudioTrack("ARDAMSa0", audioSource));

        MediaConstraints pcConstraints = makePcConstraints();
        PeerConnection pc = null;
        final MyPcObserver observer = new MyPcObserver(new WebRtcObserver(pc, pcConstraints,
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

    @NonNull
    private MediaConstraints getMediaConstraints() {
        MediaConstraints mSdpConstraints = new MediaConstraints();
        mSdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "OfferToReceiveAudio", "false"));
        mSdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "OfferToReceiveVideo", VIDEO_ENABLED + ""));
        return mSdpConstraints;
    }

    public void answer(long callId) {
        weAreOfferer = false;
        this.callId = callId;
        sVcHandler.post(new Runnable() {
            @Override
            public void run() {
                doAnswer();
            }
        });
    }

    private void doAnswer() {

        // Uncomment to get ALL WebRTC tracing and SENSITIVE libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
//		Logging.enableTracing("logcat:",
//				EnumSet.of(Logging.TraceLevel.TRACE_ALL),
//				Logging.Severity.LS_SENSITIVE);
        if (haveOffer) {
            Log.d(TAG, "doAnswer: haveOffer");
            connectionObserver = createObserver(false);
            startReporting();
        } else {
            Log.d(TAG, "doAnswer: No offer");
            allowAnswer = true;
        }


    }

    public void unpublish() {
        if (inited) {
            if (unpublishing) {
                return;
            }
            unpublishing = true;
            sVcHandler.post(new Runnable() {
                @Override
                public void run() {
                    doUnpublish();
                }
            });
        } else {
            finishParentActivity();
        }
    }

    /**
     * stop all streams from being cast to the server
     */
    private void doUnpublish() {
        if (connectionObserver == null) {
            finishParentActivity();
            return;
        }
        connectionObserver.getSdpObserver().pc.removeStream(lMS);

        destroy(connectionObserver.getSdpObserver().pc);

        if (lMS != null) {
            lMS.dispose();
        }
        if (mVideoCapturer != null) {
            mVideoCapturer.dispose();
        }

        lMS = null;
        mVideoCapturer = null;
        if (mVideoSource != null && !mVideoStopped) {
            mVideoSource.stop();
        }
        mVideoSource = null;

        finishParentActivity();

    }

    private void finishParentActivity() {
        if (callFragment != null && callFragment.getActivity() != null) {
            callFragment.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (callFragment.getActivity() != null) {
                        callFragment.getActivity().finish();
                    }
                }
            });
        }
    }


    private void destroy(final PeerConnection pc) {
        sVcHandler.post(new Runnable() {
            @Override
            public void run() {
                if (pc != null) {
                    pc.close();
                    pc.dispose();
                }


            }
        });
    }




    public MediaConstraints makePcConstraints() {
        MediaConstraints pcConstraints = new MediaConstraints();

        pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
                "RtpDataChannels", "true"));
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
                "EnableDtlsSrtp", "true"));
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
                "DtlsSrtpKeyAgreement", "true"));
        return pcConstraints;
    }


    public boolean isInited() {
        return inited;
    }
}
