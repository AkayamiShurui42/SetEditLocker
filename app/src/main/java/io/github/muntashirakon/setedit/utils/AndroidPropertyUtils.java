package io.github.muntashirakon.setedit.utils;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.Shell;

import af.shizuku.Shizuku;

public final class AndroidPropertyUtils {
    @NonNull
    public static ActionResult update(@NonNull String keyName, @NonNull String newValue) {
        if (af.shizuku.Shizuku.pingBinder() && af.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                af.shizuku.ShizukuRemoteProcess process = io.github.muntashirakon.setedit.EditorUtils.newShizukuProcess(new String[]{"setprop", keyName, newValue}, null, null);
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    return new ActionResult(ActionResult.TYPE_UPDATE, true);
                } else {
                    ActionResult r = new ActionResult(ActionResult.TYPE_UPDATE, false);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    r.setLogs(sb.toString().trim());
                    return r;
                }
            } catch (Exception e) {
                ActionResult r = new ActionResult(ActionResult.TYPE_UPDATE, false);
                r.setLogs(e.getMessage());
                return r;
            }
        }
        Shell.Result result = Shell.cmd("resetprop " + keyName + " \"" + newValue + "\"").exec();
        ActionResult r = new ActionResult(ActionResult.TYPE_UPDATE, result.isSuccess());
        r.setLogs(TextUtils.join("\n", result.getErr()));
        return r;
    }
}
