package im.actor.core.api;
/*
 *  Generated by the Actor API Scheme generator.  DO NOT EDIT!
 */

import im.actor.runtime.bser.*;
import im.actor.runtime.collections.*;
import static im.actor.runtime.bser.Utils.*;
import im.actor.core.network.parser.*;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import com.google.j2objc.annotations.ObjectiveCName;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

public class ApiContentViewChanged extends ApiEvent {

    private String contentType;
    private String contentId;
    private boolean visible;
    private ApiRawValue params;

    public ApiContentViewChanged(@NotNull String contentType, @NotNull String contentId, boolean visible, @Nullable ApiRawValue params) {
        this.contentType = contentType;
        this.contentId = contentId;
        this.visible = visible;
        this.params = params;
    }

    public ApiContentViewChanged() {

    }

    public int getHeader() {
        return 2;
    }

    @NotNull
    public String getContentType() {
        return this.contentType;
    }

    @NotNull
    public String getContentId() {
        return this.contentId;
    }

    public boolean visible() {
        return this.visible;
    }

    @Nullable
    public ApiRawValue getParams() {
        return this.params;
    }

    @Override
    public void parse(BserValues values) throws IOException {
        this.contentType = values.getString(1);
        this.contentId = values.getString(2);
        this.visible = values.getBool(3);
        if (values.optBytes(4) != null) {
            this.params = ApiRawValue.fromBytes(values.getBytes(4));
        }
        if (values.hasRemaining()) {
            setUnmappedObjects(values.buildRemaining());
        }
    }

    @Override
    public void serialize(BserWriter writer) throws IOException {
        if (this.contentType == null) {
            throw new IOException();
        }
        writer.writeString(1, this.contentType);
        if (this.contentId == null) {
            throw new IOException();
        }
        writer.writeString(2, this.contentId);
        writer.writeBool(3, this.visible);
        if (this.params != null) {
            writer.writeBytes(4, this.params.buildContainer());
        }
        if (this.getUnmappedObjects() != null) {
            SparseArray<Object> unmapped = this.getUnmappedObjects();
            for (int i = 0; i < unmapped.size(); i++) {
                int key = unmapped.keyAt(i);
                writer.writeUnmapped(key, unmapped.get(key));
            }
        }
    }

    @Override
    public String toString() {
        String res = "struct ContentViewChanged{";
        res += "contentType=" + this.contentType;
        res += ", visible=" + this.visible;
        res += ", params=" + this.params;
        res += "}";
        return res;
    }

}
