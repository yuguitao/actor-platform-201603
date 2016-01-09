package im.actor.core.js.modules;

import com.google.gwt.typedarrays.shared.ArrayBuffer;
import com.google.gwt.typedarrays.shared.TypedArrays;
import com.google.gwt.typedarrays.shared.Uint8Array;

import java.util.HashMap;
import java.util.HashSet;

import im.actor.core.api.ApiFileLocation;
import im.actor.core.api.rpc.RequestGetFileUrl;
import im.actor.core.api.rpc.ResponseGetFileUrl;
import im.actor.core.modules.AbsModule;
import im.actor.core.modules.ModuleContext;
import im.actor.core.network.RpcCallback;
import im.actor.core.network.RpcException;
import im.actor.runtime.crypto.Base64Utils;
import im.actor.runtime.js.fs.JsBlob;
import im.actor.runtime.js.fs.JsFileLoadedClosure;
import im.actor.runtime.js.fs.JsFileReader;
import im.actor.runtime.js.http.JsHttpRequest;
import im.actor.runtime.js.http.JsHttpRequestHandler;

public class JsSmallAvatarFileCache extends AbsModule {

    private HashMap<Long, String> cachedImages = new HashMap<Long, String>();
    private HashSet<Long> requested = new HashSet<Long>();

    public JsSmallAvatarFileCache(ModuleContext context) {
        super(context);
    }

    public String getSmallAvatar(long id, long accessHash) {
        if (cachedImages.containsKey(id)) {
            // Support returning nulls intentionally
            return cachedImages.get(id);
        }
        loadAvatar(id, accessHash);
        return null;
    }

    private void loadAvatar(final long id, long accessHash) {
        if (requested.contains(id)) {
            return;
        }
        request(new RequestGetFileUrl(new ApiFileLocation(id, accessHash)),
                new RpcCallback<ResponseGetFileUrl>() {
                    @Override
                    public void onResult(ResponseGetFileUrl response) {
                        onAvatarUrlLoaded(id, response.getUrl());
                    }

                    @Override
                    public void onError(RpcException e) {
                        cachedImages.put(id, null);
                    }
                });
    }

    private void onAvatarUrlLoaded(final long id, String url) {
        JsHttpRequest request = JsHttpRequest.create();
        request.open("GET", url);
        request.setOnLoadHandler(new JsHttpRequestHandler() {
            @Override
            public void onStateChanged(JsHttpRequest request) {
                if (request.getReadyState() == 4) {
                    if (request.getStatus() == 200) {
                        JsBlob blob = request.getResponseBlob();
                        onAvatarDownloaded(id, blob);
                    } else {
                        // TODO: Implement better
                        cachedImages.put(id, null);
                    }
                }
            }
        });
        request.send();
    }

    private void onAvatarDownloaded(final long id, JsBlob blob) {
        JsFileReader fileReader = JsFileReader.create();
        fileReader.setOnLoaded(new JsFileLoadedClosure() {
            @Override
            public void onLoaded(ArrayBuffer message) {
                Uint8Array array = TypedArrays.createUint8Array(message);
                byte[] data = new byte[array.length()];
                for (int i = 0; i < array.length(); i++) {
                    data[i] = (byte) (array.get(i));
                }
                String base64 = Base64Utils.toBase64(data);
                cachedImages.put(id, "data:image/jpeg;base64," + base64);
            }
        });
        fileReader.readAsArrayBuffer(blob.slice(0, blob.getSize()));
    }
}
