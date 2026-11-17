package com.snapsave.app.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import com.snapsave.app.engine.MediaFiles;

import java.util.IdentityHashMap;
import java.util.Map;

/** On-demand public Downloads permission, only for Android 6 through Android 9. */
public final class StoragePermission {
    private static final int REQUEST = 1104;
    private static final Map<Activity, Runnable> pending = new IdentityHashMap<Activity, Runnable>();
    private static final Map<Activity, AlertDialog> dialogs = new IdentityHashMap<Activity, AlertDialog>();

    private StoragePermission() { }

    public static void run(Activity activity, Runnable granted) {
        if (!alive(activity)) return;
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 29
                || activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            granted.run();
            return;
        }
        if (pending.containsKey(activity)) return;
        pending.put(activity, granted);
        try {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST);
        } catch (RuntimeException e) {
            pending.remove(activity);
            Toast.makeText(activity, "Allow storage access in Settings, then retry the download.", Toast.LENGTH_LONG).show();
        }
    }

    public static boolean onResult(final Activity activity, int requestCode,
                                   String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST) return false;
        Runnable granted = pending.remove(activity);
        if (!alive(activity) || granted == null) return true;
        if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            granted.run();
        } else {
            AlertDialog previous = dialogs.remove(activity);
            if (previous != null) previous.dismiss();
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Storage access needed")
                    .setMessage("Allow storage access to save in " + MediaFiles.locationLabel()
                            + ". Then tap Download again.")
                    .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            try {
                                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + activity.getPackageName())));
                            } catch (RuntimeException ignored) {
                                Toast.makeText(activity, "Open app permissions in Settings.", Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null).create();
            dialogs.put(activity, dialog);
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface dismissed) {
                    if (dialogs.get(activity) == dialog) dialogs.remove(activity);
                }
            });
            dialog.show();
        }
        return true;
    }

    public static void cancel(Activity activity, Runnable granted) {
        if (pending.get(activity) == granted) pending.remove(activity);
    }

    public static void onDestroy(Activity activity) {
        pending.remove(activity);
        AlertDialog dialog = dialogs.remove(activity);
        if (dialog != null) dialog.dismiss();
    }

    private static boolean alive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }
}
