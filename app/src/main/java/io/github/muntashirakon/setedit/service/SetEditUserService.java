package io.github.muntashirakon.setedit.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku UserService used for privileged command execution.
 *
 * This process is created by Shizuku and therefore runs as shell (ADB) or root,
 * avoiding the deprecated Shizuku.newProcess() path entirely.
 */
public final class SetEditUserService extends ISetEditUserService.Stub {
    private static final String TAG = "SetEditUserService";

    public SetEditUserService() {
        Log.i(TAG, "created");
    }

    @Keep
    public SetEditUserService(Context context) {
        Log.i(TAG, "created with context");
    }

    @Override
    public void destroy() {
        Log.i(TAG, "destroy");
        System.exit(0);
    }

    @Override
    public String execute(String[] command) {
        if (command == null || command.length == 0) {
            return "-1\nEmpty command";
        }

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            return exitCode + "\n" + output;
        } catch (Throwable t) {
            String message = t.getMessage();
            return "-1\n" + t.getClass().getName()
                    + (message == null || message.isEmpty() ? "" : ": " + message);
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
