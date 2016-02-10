package im.actor.sdk.controllers.calls;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

import im.actor.runtime.actors.Actor;

public class TimerActor extends Actor {
    private final int inteval;
    private HashSet<TimerCallback> callbacks = new HashSet<TimerCallback>();
    private HashMap<TimerCallback, Long> callbacksRegisterTime = new HashMap<TimerCallback, Long>();
    public TimerActor(int inteval) {
        this.inteval = inteval;
    }

    @Override
    public void preStart() {
        super.preStart();
        self().send(new Tick(), inteval);
    }

    @Override
    public void onReceive(Object message) {
        if(message instanceof Register){
            callbacks.add(((Register) message).getCallback());
            callbacksRegisterTime.put(((Register) message).getCallback(), System.currentTimeMillis());
        }else if(message instanceof UnRegister){
            callbacks.remove(((UnRegister) message).getCallback());
            callbacksRegisterTime.remove(((UnRegister) message).getCallback());
        }else if(message instanceof Tick){
            onTick();
        }
    }

    private void onTick() {
        for (TimerCallback callback:callbacks) {
            long currentTime = System.currentTimeMillis();
            callback.onTick(currentTime, System.currentTimeMillis() - callbacksRegisterTime.get(callback));
        }
        self().send(new Tick(), inteval);
    }

    public static class Register{
        TimerCallback callback;

        public Register(TimerCallback callback) {
            this.callback = callback;
        }

        public TimerCallback getCallback() {
            return callback;
        }
    }

    public static class UnRegister{
        TimerCallback callback;

        public UnRegister(TimerCallback callback) {
            this.callback = callback;
        }

        public TimerCallback getCallback() {
            return callback;
        }
    }

    private static class Tick{

    }

    public interface TimerCallback{
        void onTick(long currentTime, long timeFromRegister);
    }
}
