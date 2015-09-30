package im.actor.core.api.rpc;
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
import im.actor.core.api.*;

public class ResponseLoadWallpappers extends Response {

    public static final int HEADER = 0xf2;
    public static ResponseLoadWallpappers fromBytes(byte[] data) throws IOException {
        return Bser.parse(new ResponseLoadWallpappers(), data);
    }

    private List<ApiWallpapper> wallpappers;

    public ResponseLoadWallpappers(@NotNull List<ApiWallpapper> wallpappers) {
        this.wallpappers = wallpappers;
    }

    public ResponseLoadWallpappers() {

    }

    @NotNull
    public List<ApiWallpapper> getWallpappers() {
        return this.wallpappers;
    }

    @Override
    public void parse(BserValues values) throws IOException {
        List<ApiWallpapper> _wallpappers = new ArrayList<ApiWallpapper>();
        for (int i = 0; i < values.getRepeatedCount(1); i ++) {
            _wallpappers.add(new ApiWallpapper());
        }
        this.wallpappers = values.getRepeatedObj(1, _wallpappers);
    }

    @Override
    public void serialize(BserWriter writer) throws IOException {
        writer.writeRepeatedObj(1, this.wallpappers);
    }

    @Override
    public String toString() {
        String res = "tuple LoadWallpappers{";
        res += "}";
        return res;
    }

    @Override
    public int getHeaderKey() {
        return HEADER;
    }
}
