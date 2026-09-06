package rikka.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;

import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;

/**
 * SetEditLocker's Shizuku binder endpoint.
 *
 * Native Shizuku+ currently uses its own provider payload and a v13 attach handshake
 * whose raw binder transaction differs from the stable stock client. For Plus binders
 * we initialize the stock client state locally and perform the Plus attach transaction
 * ourselves. Stock Shizuku continues through Shizuku.onBinderReceived normally.
 */
public final class SetEditShizukuProvider extends ContentProvider {
    private static final String TAG = "SetEditShizukuProvider";

    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String METHOD_GET_BINDER = "getBinder";

    private static final String PLUS_EXTRA = "af.shizuku.plus.api.intent.extra.BINDER";
    private static final String STOCK_EXTRA = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private static final String LEGACY_EXTRA = "rikka.shizuku.intent.extra.BINDER";

    // Shizuku+ API fork's raw v13 attachApplication transaction.
    private static final int PLUS_ATTACH_APPLICATION_TRANSACTION = 17;
    private static final String SHIZUKU_DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    private static final String ATTACH_API_VERSION = "shizuku:attach-api-version";
    private static final String ATTACH_PACKAGE_NAME = "shizuku:attach-package-name";
    private static final int CLIENT_API_VERSION = 13;

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

        // Native Plus has a unique key, so do not run it through stock onBinderReceived.
        IBinder plusBinder = readPlusBinder(extras, PLUS_EXTRA);
        if (plusBinder != null && plusBinder.pingBinder()) {
            Context context = getContext();
            if (context != null && initializePlusClient(plusBinder, context.getPackageName())) {
                Log.i(TAG, "Received and attached native Shizuku+ binder");
                return;
            }
            Log.w(TAG, "Native Shizuku+ binder attach failed");
            return;
        }

        IBinder binder = readStockBinder(extras, STOCK_EXTRA);
        if (binder == null) binder = readStockBinder(extras, LEGACY_EXTRA);
        if (binder == null) binder = readPlusBinder(extras, LEGACY_EXTRA);

        if (binder == null || !binder.pingBinder()) {
            Log.w(TAG, "Binder delivery did not contain a live Shizuku binder");
            return;
        }

        Context context = getContext();
        if (context == null) return;
        Shizuku.onBinderReceived(binder, context.getPackageName());
        Log.i(TAG, "Received stock-compatible Shizuku binder");
    }

    /**
     * Wire a native Plus binder into the stable Rikka client without invoking the stock
     * attachApplication path. The Plus fork's own client performs this same raw v13 attach
     * using transaction 17. Its callback then populates permission/version state and fires
     * the normal Shizuku binder-received listeners.
     */
    private boolean initializePlusClient(@NonNull IBinder binder, @NonNull String packageName) {
        try {
            resetClientState();
            setStaticField("binder", binder);
            setStaticField("service", IShizukuService.Stub.asInterface(binder));

            Field applicationField = Shizuku.class.getDeclaredField("SHIZUKU_APPLICATION");
            applicationField.setAccessible(true);
            IShizukuApplication application = (IShizukuApplication) applicationField.get(null);
            if (application == null) {
                throw new IllegalStateException("Shizuku application callback unavailable");
            }

            Bundle args = new Bundle();
            args.putInt(ATTACH_API_VERSION, CLIENT_API_VERSION);
            args.putString(ATTACH_PACKAGE_NAME, packageName);

            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(SHIZUKU_DESCRIPTOR);
                data.writeStrongBinder(application.asBinder());
                data.writeInt(1);
                args.writeToParcel(data, 0);

                boolean handled = binder.transact(
                        PLUS_ATTACH_APPLICATION_TRANSACTION, data, reply, 0);
                if (!handled) {
                    throw new IllegalStateException("Shizuku+ attachApplication transaction was not handled");
                }
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }

            // The callback above marks binderReady and dispatches the normal listeners.
            // Keep a death hook so the stable client's state is cleared on Plus restart.
            binder.linkToDeath(() -> {
                try {
                    Shizuku.onBinderReceived(null, null);
                } catch (Throwable t) {
                    Log.w(TAG, "Unable to clear dead Shizuku+ binder", t);
                }
            }, 0);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to attach native Shizuku+ binder", t);
            try {
                Shizuku.onBinderReceived(null, null);
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    private void resetClientState() throws ReflectiveOperationException {
        setStaticField("binder", null);
        setStaticField("service", null);
        setStaticField("serverUid", -1);
        setStaticField("serverApiVersion", -1);
        setStaticField("serverPatchVersion", -1);
        setStaticField("serverContext", null);
        setStaticField("permissionGranted", false);
        setStaticField("shouldShowRequestPermissionRationale", false);
        setStaticField("preV11", false);
        setStaticField("binderReady", false);
    }

    private void setStaticField(@NonNull String name, @Nullable Object value)
            throws ReflectiveOperationException {
        Field field = Shizuku.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
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
