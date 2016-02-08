package im.actor.sdk.calls;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Vibrator;
import android.support.v4.content.ContextCompat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.webrtc.IceCandidate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import im.actor.core.api.rpc.ResponseDoCall;
import im.actor.core.entity.Peer;
import im.actor.core.viewmodel.Command;
import im.actor.core.viewmodel.CommandCallback;
import im.actor.core.viewmodel.UserVM;
import im.actor.runtime.Log;
import im.actor.runtime.actors.ActorCreator;
import im.actor.runtime.actors.ActorRef;
import im.actor.runtime.actors.ActorSystem;
import im.actor.runtime.actors.Props;
import im.actor.sdk.R;
import im.actor.sdk.controllers.fragment.BaseFragment;
import im.actor.sdk.core.AndroidCalls;
import im.actor.sdk.core.audio.AndroidPlayerActor;
import im.actor.sdk.core.audio.AudioPlayerActor;
import im.actor.sdk.view.avatar.CoverAvatarView;

import static im.actor.sdk.util.ActorSDKMessenger.messenger;
import static im.actor.sdk.util.ActorSDKMessenger.users;

/**
 * simple fragment to display two rows of video streams
 */
public class CallFragment extends BaseFragment {
    private static final int PERMISSIONS_REQUEST_FOR_CALL = 147;
    long callId = -1;
    Peer peer;
    // TODO replace this with your servers url!
    /** server url - where to request tokens */


    /**
     * the licode signaling engine
     */
    WEBRTCConnector mConnector = null;
    /**
     * the container for all the videos
     */
    VideoGridLayout mContainer = null;
    /**
     * the video streams view
     */
    VideoStreamsView mVsv = null;
    /**
     * map of stream id -> video view
     */
    boolean incoming;
    ConcurrentHashMap<String, VideoStreamPlaceholder> mVideoViews = new ConcurrentHashMap<String, VideoStreamPlaceholder>();
    private Vibrator v;
    private View answerContainer;
    private Ringtone ringtone;
    private boolean allowAnswer = false;
    private boolean permissionsOk = false;
    private boolean readyToCall = false;
    private ActorRef toneActor;


    public CallFragment() {

    }

    public CallFragment(long callId, boolean incoming) {
        this.callId = callId;
        this.peer = messenger().getCall(callId).getPeer();
        this.incoming = incoming;
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {


        setHasOptionsMenu(true);

        FrameLayout cont = (FrameLayout) inflater.inflate(R.layout.fragment_videochat, container, false);
        Button b = (Button) cont.findViewById(R.id.start);
        v = (Vibrator) getActivity().getSystemService(Context.VIBRATOR_SERVICE);

        answerContainer = cont.findViewById(R.id.answer_container);
        ImageButton answer = (ImageButton) cont.findViewById(R.id.answer);
        answer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAnswer();
            }
        });
        ImageButton notAnswer = (ImageButton) cont.findViewById(R.id.notAnswer);
        ImageButton endCall = (ImageButton) cont.findViewById(R.id.end_call);
        notAnswer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onNotAnswer();
            }
        });
        endCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                messenger().endCall(callId);
                onCallEnd();
            }
        });

        //Avatar/Name
        CoverAvatarView avatarView = (CoverAvatarView) cont.findViewById(R.id.avatar);
        avatarView.setBkgrnd((ImageView) cont.findViewById(R.id.avatar_bgrnd));

        final UserVM user = users().get(peer.getPeerId());
        bind(avatarView, user.getAvatar());
        bind((TextView) cont.findViewById(R.id.name), user.getName());
        ///
        View mainView = cont.findViewById(R.id.videochat_grid);
        b.setVisibility(View.INVISIBLE);

        mContainer = (VideoGridLayout) mainView
                .findViewById(R.id.videochat_grid);
        if (WEBRTCConnector.VIDEO_ENABLED) {
            mContainer.setCollapsed(false);

            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mContainer.setGridDimensions(9, 1);
            } else {
                mContainer.setGridDimensions(3, 3);
            }


            mVsv = new VideoStreamsView(this.getActivity());
            makeVideoView(VideoStreamsView.LOCAL_STREAM_ID);
//        makeVideoView(VideoStreamsView.REMOTE_STREAM_ID);
            mContainer.addView(mVsv);
            mContainer.setVideoElement(mVsv);
        } else {
            mContainer.setCollapsed(true);
            mContainer.setVisibility(View.INVISIBLE);
        }


        mConnector = new WEBRTCConnector(getActivity(), CallFragment.this, mVsv);

        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED) {
            permissionsOk = false;
            Log.d("Permissions", "call - no permission :c");
            CallFragment.this.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.VIBRATE, Manifest.permission.WAKE_LOCK},
                    PERMISSIONS_REQUEST_FOR_CALL);

        } else {
            permissionsOk = true;
        }

        startChat();
        if (incoming) {
            initIncoming();
        }

        if (permissionsOk) {
            mConnector.init(null);
        }


        return cont;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_FOR_CALL) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (!mConnector.isInited()) {
                    mConnector.init(null);
                } else {
                    permissionsOk = true;
                }
            } else {
                messenger().endCall(callId);
            }
        }
    }


    private String readStream(InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private void initIncoming() {
        answerContainer.setVisibility(View.VISIBLE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1100);
                    Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                    ringtone = RingtoneManager.getRingtone(getActivity(), notification);
                    if (getActivity() != null & answerContainer.getVisibility() == View.VISIBLE) {
                        if (ringtone != null) {
                            ringtone.play();
                        }
                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }).start();
    }

    private void onAnswer() {
        AndroidCalls.getController().answerCall();
        onConnecting();
        answerContainer.setVisibility(View.INVISIBLE);
        if (ringtone != null) {
            ringtone.stop();
        }


        if (mConnector.isInited()) {
            mConnector.answer(callId);
        } else {
            allowAnswer = true;
        }
    }

    private void onNotAnswer() {
        messenger().endCall(callId);
        onCallEnd();
    }


    ArrayList<String> offers = new ArrayList<String>();
    ArrayList<String> answers = new ArrayList<String>();
    ArrayList<IceCandidate> candidates = new ArrayList<IceCandidate>();

    private void startChat() {

        final CallCallback callCallback = new CallCallback() {
            @Override
            public void onCallEnd() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            CallFragment.this.onCallEnd();
                        }
                    });
                }

            }

            @Override
            public void onAnswer(long callId, final String offerSDP) {
                if(callId!=CallFragment.this.callId){
                    return;
                }
                if (mConnector.isInited()) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mConnector.onAnswer(offerSDP);
                        }
                    });
                } else {
                    answers.add(offerSDP);
                }
            }

            @Override
            public void onOffer(long callId, final String offerSDP) {
                if(callId!=CallFragment.this.callId){
                    return;
                }
                if (mConnector.isInited()) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mConnector.onOffer(offerSDP);
                        }
                    });
                } else {
                    offers.add(offerSDP);
                }
            }

            @Override
            public void onCandidate(long callId, String id, int label, String sdp) {
                if(callId!=CallFragment.this.callId){
                    return;
                }
                final IceCandidate candidate = new IceCandidate(id, label, sdp);
                if (mConnector.isInited()) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mConnector.onCandidate(candidate);
                        }
                    });
                } else {
                    candidates.add(candidate);
                }
            }


            @Override
            public void onOfferNeeded() {
                mConnector.onOfferNeeded();
            }
        };

        if (incoming) {
            //

        } else {
            onConnecting();
            if (mConnector.isInited()) {
                mConnector.call(callId);
            } else {
                readyToCall = true;
            }
        }
        AndroidCalls.handleCall(callCallback);


    }


    public void onConnectorInited() {
        for (String answer : answers) {
            mConnector.onAnswer(answer);
        }

        for (String offer : offers) {
            mConnector.onOffer(offer);
        }

        for (IceCandidate candidate: candidates) {
            mConnector.onCandidate(candidate);
        }

        if (!incoming) {
            if (readyToCall) {
                mConnector.call(callId);
            }
        } else {
            if (allowAnswer) {
                mConnector.answer(callId);
            }
        }

        AndroidCalls.getController().readyForCandidates();
    }


    boolean vibrate = true;

    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private int field = 0x00000020;


    public void onConnecting() {
        if (toneActor == null) {
            toneActor = ActorSystem.system().actorOf(Props.create(new ActorCreator() {
                @Override
                public AudioActorEx create() {
                    return new AudioActorEx(getActivity(), new AudioPlayerActor.AudioPlayerCallback() {
                        @Override
                        public void onStart(String fileName) {

                        }

                        @Override
                        public void onStop(String fileName) {

                        }

                        @Override
                        public void onPause(String fileName, float progress) {

                        }

                        @Override
                        public void onProgress(String fileName, float progress) {

                        }

                        @Override
                        public void onError(String fileName) {

                        }
                    });
                }
            }), "actor/android_tone");
        }

        toneActor.send(new AndroidPlayerActor.Play(""));

        toastInCenter("conectando...");
        vibrate = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (vibrate) {
                    try {
                        v.vibrate(10);
                        Thread.sleep(5);
                        v.vibrate(10);
                        Thread.sleep(200);
                        v.vibrate(10);
                        Thread.sleep(5);
                        v.vibrate(10);
                        Thread.sleep(600);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();

        powerManager = (PowerManager) getActivity().getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(field, getActivity().getLocalClassName());

        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }


    public void onConnected() {
        if (toneActor != null) {
            toneActor.send(new AndroidPlayerActor.Stop());
        }
        vibrate = false;
        v.cancel();
        toastInCenter("conectando!");
        v.vibrate(200);

    }

    public void onCallEnd() {
        if (toneActor != null) {
            toneActor.send(new AndroidPlayerActor.Stop());
        }
        vibrate = false;
        if (ringtone != null) {
            ringtone.stop();
        }
        if (v != null) {
            v.cancel();
        }
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
        mConnector.unpublish();


    }

    /**
     * create or retrieve a display element for given stream - will add this to
     * the appropriate list and the container element for video streams.
     *
     * @param streamId The source of the video data.
     * @return An existing video display element, or a newly created one.
     */
    protected VideoStreamPlaceholder makeVideoView(String streamId) {
        mVsv.addStream(streamId);
        if (mVideoViews.containsKey(streamId)) {
            return mVideoViews.get(streamId);
        } else if (getActivity() != null) {
            VideoStreamPlaceholder vsp = new VideoStreamPlaceholder(
                    getActivity(), mVsv, streamId);
//            vsp.setOnClickListener(mVsvClickListener);

            mVideoViews.put(streamId, vsp);
            mContainer.addView(vsp);
            return vsp;
        }

        // no activity? this is a dead fragment
        return null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//		Logging.enableTracing(
//				"logcat:",
//				EnumSet.of(Logging.TraceLevel.TRACE_ALL),
//				Logging.Severity.LS_SENSITIVE);
    }


    @Override
    public void onStop() {
        super.onStop();

        if (mConnector != null) {
            //TODO kill streams
        }
    }


    @Override
    public void onPause() {
        super.onPause();
        if (WEBRTCConnector.VIDEO_ENABLED) {
            mVsv.onPause();
        }
//        onCallEnd();
        // mConnector.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (WEBRTCConnector.VIDEO_ENABLED) {
            mVsv.onResume();
        }
        // mConnector.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mConnector != null) {
        }
    }


    private void toastInCenter(String s) {
        Toast t = Toast.makeText(getActivity(),
                s, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
    }

    public interface VideoCallBack {
        void onStream();
    }

    public interface CallCallback {
        void onCallEnd();

        void onAnswer(long callId, String offerSDP);

        void onOffer(long callId, String offerSDP);

        void onCandidate(long callId, String id, int label, String sdp);

        void onOfferNeeded();
    }
}
