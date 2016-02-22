package im.actor.core.modules.calls.peers;

import java.util.ArrayList;

import im.actor.core.modules.ModuleContext;
import im.actor.core.modules.calls.peers.messages.RTCAdvertised;
import im.actor.core.modules.calls.peers.messages.RTCAnswer;
import im.actor.core.modules.calls.peers.messages.RTCCandidate;
import im.actor.core.modules.calls.peers.messages.RTCNeedOffer;
import im.actor.core.modules.calls.peers.messages.RTCOffer;
import im.actor.core.modules.calls.peers.messages.RTCOwnStart;
import im.actor.core.modules.calls.peers.messages.RTCStart;
import im.actor.core.util.ModuleActor;
import im.actor.runtime.webrtc.WebRTCMediaStream;

/**
 * Proxy Actor for simplifying state of PeerConnection by careful peer connection initialization
 * and handling case when we want to establish connection before call answering
 */
public class PeerNodeActor extends ModuleActor implements PeerConnectionCallback {

    private final long deviceId;
    private final PeerNodeCallback callback;
    private final PeerSettings ownSettings;
    private final ArrayList<WebRTCMediaStream> theirMediaStreams = new ArrayList<>();

    private PeerConnectionInt peerConnection;
    private PeerSettings theirSettings;
    private WebRTCMediaStream ownMediaStream;
    private boolean isTheirEnabled = false;
    private boolean isOwnEnabled = false;
    private boolean isEnabled = false;
    private boolean isConnected = false;
    private boolean isStarted = false;

    public PeerNodeActor(long deviceId,
                         PeerSettings ownSettings,
                         PeerNodeCallback callback,
                         ModuleContext context) {
        super(context);
        this.deviceId = deviceId;
        this.ownSettings = ownSettings;
        this.callback = callback;
    }


    //
    // Starting up peer nodes
    //
    // Stages:
    // 1. Waiting for advertise of a node
    // 2. If both peers supports pre connection, create new connection
    // 3. Enabling Own and Their peers
    // 4. Setting own media stream
    // 5. Creation of peer connection
    //

    public void onAdvertised(PeerSettings settings) {
        if (this.theirSettings == null) {
            this.theirSettings = settings;
            reconfigurePeerConnectionIfNeeded();
        }
    }

    public void onEnableOwn() {
        if (!this.isOwnEnabled) {
            this.isOwnEnabled = true;
            reconfigurePeerConnectionIfNeeded();
        }
    }

    public void onEnableTheir() {
        if (!this.isTheirEnabled) {
            this.isTheirEnabled = true;
            reconfigurePeerConnectionIfNeeded();
        }
    }

    public void setOwnSetStream(WebRTCMediaStream mediaStream) {
        if (this.ownMediaStream == null) {
            this.ownMediaStream = mediaStream;
            reconfigurePeerConnectionIfNeeded();
        }
    }

    private void reconfigurePeerConnectionIfNeeded() {
        makePeerConnectionIfNeeded();
        enablePeerConnectionIfNeeded();
        startIfNeeded();
    }

    private void makePeerConnectionIfNeeded() {
        if (peerConnection != null || theirSettings == null || ownMediaStream == null) {
            return;
        }

        if ((isOwnEnabled && isTheirEnabled) ||
                (theirSettings.isPreConnectionEnabled() && ownSettings.isPreConnectionEnabled())) {

            peerConnection = new PeerConnectionInt(
                    ownSettings, theirSettings,
                    ownMediaStream, this, context(), self(), "connection");

            unstashAll();
        }
    }

    private void enablePeerConnectionIfNeeded() {
        if (peerConnection == null || !isOwnEnabled || !isTheirEnabled || isEnabled) {
            return;
        }
        isEnabled = true;

        for (WebRTCMediaStream mediaStream : theirMediaStreams) {
            mediaStream.setEnabled(true);
        }
    }

    private void startIfNeeded() {
        if (isEnabled && isConnected && !isStarted) {
            isStarted = true;

            callback.onConnectionStarted(deviceId);
            for (WebRTCMediaStream mediaStream : theirMediaStreams) {
                callback.onStreamAdded(deviceId, mediaStream);
            }
        }
    }


    //
    // Peer callbacks
    //

    @Override
    public void onOffer(String sdp) {
        callback.onOffer(deviceId, sdp);
    }

    @Override
    public void onAnswer(String sdp) {
        callback.onAnswer(deviceId, sdp);
    }

    @Override
    public void onCandidate(int mdpIndex, String id, String sdp) {
        callback.onCandidate(deviceId, mdpIndex, id, sdp);
    }

    @Override
    public void onStreamAdded(WebRTCMediaStream stream) {
        theirMediaStreams.add(stream);
        stream.setEnabled(isEnabled);
        if (isStarted) {
            callback.onStreamAdded(deviceId, stream);
        }

        if (!isConnected) {
            isConnected = true;
            if (!isEnabled) {
                callback.onConnectionEstablished(deviceId);
            } else {
                // This case is handled in startIfNeeded();
            }
        }

        startIfNeeded();
    }

    @Override
    public void onStreamRemoved(WebRTCMediaStream stream) {
        theirMediaStreams.remove(stream);
        if (isStarted) {
            callback.onStreamRemoved(deviceId, stream);
        }
    }


    //
    // Stopping
    //

    @Override
    public void postStop() {
        if (peerConnection != null) {
            peerConnection.kill();
            peerConnection = null;
        }
    }


    //
    // Messages
    //

    @Override
    public void onReceive(Object message) {
        if (message instanceof RTCStart) {
            onEnableTheir();
        } else if (message instanceof RTCOwnStart) {
            onEnableOwn();
        } else if (message instanceof RTCAdvertised) {
            RTCAdvertised advertised = (RTCAdvertised) message;
            onAdvertised(advertised.getSettings());
        } else if (message instanceof SetOwnStream) {
            SetOwnStream ownStream = (SetOwnStream) message;
            setOwnSetStream(ownStream.getMediaStream());
        } else if (message instanceof RTCOffer) {
            if (peerConnection == null) {
                stash();
                return;
            }
            RTCOffer offer = (RTCOffer) message;
            peerConnection.onOffer(offer.getSdp());
        } else if (message instanceof RTCAnswer) {
            if (peerConnection == null) {
                stash();
                return;
            }
            RTCAnswer answer = (RTCAnswer) message;
            peerConnection.onAnswer(answer.getSdp());
        } else if (message instanceof RTCNeedOffer) {
            if (peerConnection == null) {
                stash();
                return;
            }
            peerConnection.onOfferNeeded();
        } else if (message instanceof RTCCandidate) {
            if (peerConnection == null) {
                stash();
                return;
            }
            RTCCandidate candidate = (RTCCandidate) message;
            peerConnection.onCandidate(candidate.getMdpIndex(), candidate.getId(), candidate.getSdp());
        } else {
            super.onReceive(message);
        }
    }

    public static class SetOwnStream {

        private WebRTCMediaStream mediaStream;

        public SetOwnStream(WebRTCMediaStream mediaStream) {
            this.mediaStream = mediaStream;
        }

        public WebRTCMediaStream getMediaStream() {
            return mediaStream;
        }
    }
}