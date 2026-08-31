package rikka.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * SetEditLocker's Shizuku binder endpoint.
 *
 * Accepts the native Shizuku+ binder payload as well as the original stock Shizuku
 * payload. The resulting binder is handed to the normal Rikka Shizuku client API,
 * so the rest of SetEditLocker does not need provider-specific code.
 */
public final class SetEditShizukuProvider extends ContentProvider {
    private static final String TAG = "SetEditShizukuProvider";

    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String METHOD_GET_BINDER = "getBinder";

    private static final String PLUS_EXTRA = "af.shizuku.plus.api.intent.extra.BINDER";
    private static final String STOCK_EXTRA = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private static final String LEGACY_EXTRA = "rikka.shizuku.intent.extra.BINDER";

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info.multiprocess) {
            throw new IllegalStateException("android:multiprocess must be false");
        }
        if (!info.exported) {
            throw new IllegalStateException("android:exported must be true");
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (METHOD_SEND_BINDER.equals(method)) {
            if (extras != null) handleSendBinder(extras);
            return new Bundle();
        }
        if (METHOD_GET_BINDER.equals(method)) {
            return buildBinderReply();
        }
        return super.call(method, arg, extras);
    }

    private void handleSendBinder(@NonNull Bundle extras) {
        if (Shizuku.pingBinder()) {
            return;
        }

        extras.setClassLoader(getClass().getClassLoader());
        IBinder binder = readPlusBinder(extras, PLUS_EXTRA);
        if (binder == null) binder = readStockBinder(extras, STOCK_EXTRA);
        if (binder == null) binder = readPlusBinder(extras, LEGACY_EXTRA);
        if (binder == null) binder = readStockBinder(extras, LEGACY_EXTRA);

        if (binder == null || !binder.pingBinder()) {
            Log.w(TAG, "Binder delivery did not contain a live Shizuku binder");
            return;
        }

        Context context = getContext();
        if (context == null) return;
        Shizuku.onBinderReceived(binder, context.getPackageName());
        Log.i(TAG, "Received Shizuku binder");
    }

    @Nullable
    private IBinder readPlusBinder(@NonNull Bundle extras, @NonNull String key) {
        try {
            af.shizuku.api.BinderContainer container = extras.getParcelable(key);
            return container != null ? container.binder : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private IBinder readStockBinder(@NonNull Bundle extras, @NonNull String key) {
        try {
            moe.shizuku.api.BinderContainer container = extras.getParcelable(key);
            return container != null ? container.binder : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private Bundle buildBinderReply() {
        IBinder binder = Shizuku.getBinder();
        if (binder == null || !binder.pingBinder()) return null;

        Bundle reply = new Bundle();
        reply.putParcelable(PLUS_EXTRA, new af.shizuku.api.BinderContainer(binder));
        reply.putParcelable(STOCK_EXTRA, new moe.shizuku.api.BinderContainer(binder));
        reply.putParcelable(LEGACY_EXTRA, new af.shizuku.api.BinderContainer(binder));
        return reply;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
