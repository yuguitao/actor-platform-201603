package im.actor.sdk.core.webrtc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
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

import im.actor.core.entity.CallState;
import im.actor.core.entity.Peer;
import im.actor.core.viewmodel.CallModel;
import im.actor.core.viewmodel.UserVM;
import im.actor.runtime.Log;
import im.actor.runtime.actors.ActorCreator;
import im.actor.runtime.actors.ActorRef;
import im.actor.runtime.actors.ActorSystem;
import im.actor.runtime.actors.Props;
import im.actor.runtime.mvvm.Value;
import im.actor.runtime.mvvm.ValueChangedListener;
import im.actor.sdk.R;
import im.actor.sdk.controllers.fragment.BaseFragment;
import im.actor.sdk.core.AndroidWebRTCProvider;
import im.actor.sdk.core.audio.AndroidPlayerActor;
import im.actor.sdk.core.audio.AudioPlayerActor;
import im.actor.sdk.view.avatar.CoverAvatarView;

import static im.actor.sdk.util.ActorSDKMessenger.messenger;
import static im.actor.sdk.util.ActorSDKMessenger.users;

public class CallFragment extends BaseFragment {
    private static final int PERMISSIONS_REQUEST_FOR_CALL = 147;
    long callId = -1;
    Peer peer;

    boolean incoming;
    private Vibrator v;
    private View answerContainer;
    private Ringtone ringtone;
    private ActorRef toneActor;
    private CallModel call;

    public CallFragment() {

    }

    public CallFragment(long callId, boolean incoming) {
        this.callId = callId;
        this.call = messenger().getCall(callId);
        this.peer = call.getPeer();
        this.incoming = incoming;
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        AndroidWebRTCProvider.initUsingActivity(getActivity());

        setHasOptionsMenu(true);

        FrameLayout cont = (FrameLayout) inflater.inflate(R.layout.fragment_call, container, false);
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

        //
        //Avatar/Name
        //
        CoverAvatarView avatarView = (CoverAvatarView) cont.findViewById(R.id.avatar);
        avatarView.setBkgrnd((ImageView) cont.findViewById(R.id.avatar_bgrnd));

        final UserVM user = users().get(peer.getPeerId());
        bind(avatarView, user.getAvatar());
        bind((TextView) cont.findViewById(R.id.name), user.getName());

        //
        // Check permission
        //
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED) {
            Log.d("Permissions", "call - no permission :c");
            CallFragment.this.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.VIBRATE, Manifest.permission.WAKE_LOCK},
                    PERMISSIONS_REQUEST_FOR_CALL);

        }

        call.getState().subscribe(new ValueChangedListener<CallState>() {
            @Override
            public void onChanged(CallState val, Value<CallState> valueModel) {
                switch (val){
                    case ENDED:
                        onCallEnd();
                        break;
                    case IN_PROGRESS:
                        onConnected();
                        break;
                }
            }
        });

        if (incoming) {
            initIncoming();
        }else{
            onConnecting();
        }


        return cont;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_FOR_CALL) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                //Permission  granted
            } else {
                messenger().endCall(callId);
            }
        }
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

        onConnecting();
        answerContainer.setVisibility(View.INVISIBLE);
        if (ringtone != null) {
            ringtone.stop();
        }

        messenger().answerCall(callId);
    }

    private void onNotAnswer() {
        messenger().endCall(callId);
    }

    //
    // Vibrate/tone/wakelock
    //
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

        getActivity().finish();

    }

    private void toastInCenter(String s) {
        Toast t = Toast.makeText(getActivity(),
                s, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
    }

}
