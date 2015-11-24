/*
 * Copyright (C) 2015 Actor LLC. <https://actor.im>
 */

package im.actor.core.modules.internal.messages;

import im.actor.core.api.ApiDialog;
import im.actor.core.api.ApiPeerType;
import im.actor.core.api.rpc.RequestLoadDialogs;
import im.actor.core.api.rpc.ResponseLoadDialogs;
import im.actor.core.entity.PeerType;
import im.actor.core.modules.ModuleContext;
import im.actor.core.modules.updates.internal.DialogHistoryLoaded;
import im.actor.core.modules.utils.ModuleActor;
import im.actor.core.network.RpcCallback;
import im.actor.core.network.RpcException;
import im.actor.runtime.Log;

public class DialogsHistoryActor extends ModuleActor {

    private static final String TAG = "DialogsHistoryActor";

    private static final int LIMIT = 20;

    private static final String KEY_LOADED_DATE = "dialogs_history_date";
    private static final String KEY_LOADED = "dialogs_history_loaded";
    private static final String KEY_LOADED_INIT = "dialogs_history_inited";

    private long historyMaxDate;
    private boolean historyLoaded;

    private boolean isLoading = false;
    int groupLimit = 0;
    int privteLimit = 0;
    public DialogsHistoryActor(ModuleContext context) {
        super(context);
    }

    @Override
    public void preStart() {
        historyMaxDate = preferences().getLong(KEY_LOADED_DATE, Long.MAX_VALUE);
        historyLoaded = preferences().getBool(KEY_LOADED, false);
        if (!preferences().getBool(KEY_LOADED_INIT, false)) {
            self().sendOnce(new LoadMore(true));
            self().sendOnce(new LoadMore(false));
        }
    }

    private void onLoadMore(boolean isGroup, boolean increment) {
        if (increment) {
            if (isGroup) {
                groupLimit = LIMIT;
            } else {
                privteLimit = LIMIT;
            }
        }

        if (historyLoaded) {
            return;
        }
        if (isLoading) {
            return;
        }
        isLoading = true;

        Log.d(TAG, "Loading history... after " + historyMaxDate);

        request(new RequestLoadDialogs(historyMaxDate, LIMIT),
                new RpcCallback<ResponseLoadDialogs>() {
                    @Override
                    public void onResult(ResponseLoadDialogs response) {
                        groupLimit -= response.getGroups().size();
                        for (ApiDialog dialog : response.getDialogs()) {
                            if (dialog.getPeer().getType() == ApiPeerType.PRIVATE) {
                                privteLimit--;
                            }
                        }
                        if (privteLimit > 0 || groupLimit > 0) {
                            self().sendOnce(new LoadMore(false, false));
                        }
                        // Invoke on sequence actor
                        updates().onUpdateReceived(new DialogHistoryLoaded(response));
                    }

                    @Override
                    public void onError(RpcException e) {
                        e.printStackTrace();
                        // Never happens
                    }
                });
    }

    private void onLoadedMore(int loaded, long maxLoadedDate) {
        isLoading = false;

        if (loaded < LIMIT) {
            historyLoaded = true;
        } else {
            historyLoaded = false;
            historyMaxDate = maxLoadedDate;
        }
        preferences().putLong(KEY_LOADED_DATE, maxLoadedDate);
        preferences().putBool(KEY_LOADED, historyLoaded);
        preferences().putBool(KEY_LOADED_INIT, true);

        Log.d(TAG, "History loaded, time = " + maxLoadedDate);
    }

    // Messages

    @Override
    public void onReceive(Object message) {
        if (message instanceof LoadMore) {
            onLoadMore(((LoadMore) message).isGroup(), ((LoadMore) message).isIncrement());
        } else if (message instanceof LoadedMore) {
            LoadedMore loaded = (LoadedMore) message;
            onLoadedMore(loaded.loaded, loaded.maxLoadedDate);
        } else {
            drop(message);
        }
    }

    public static class LoadMore {
        private boolean isGroup;
        private boolean increment;

        public LoadMore(boolean isGroup, boolean increment) {
            this.isGroup = isGroup;
            this.increment = increment;
        }

        public LoadMore(boolean isGroup) {
            this.isGroup = isGroup;
            this.increment = true;
        }

        public boolean isIncrement() {
            return increment;
        }

        public boolean isGroup() {
            return isGroup;
        }
    }

    public static class LoadedMore {
        private int loaded;
        private long maxLoadedDate;

        public LoadedMore(int loaded, long maxLoadedDate) {
            this.loaded = loaded;
            this.maxLoadedDate = maxLoadedDate;
        }
    }
}
