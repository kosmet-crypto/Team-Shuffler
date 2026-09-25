package app.teamshuffler;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.OutputStream;

/**
 * Installs a newer APK from inside the app. On Android 12+ an app updating itself may skip the
 * install screen (asked with USER_ACTION_NOT_REQUIRED); if a phone refuses, the same download is
 * retried with Android's normal install screen. Never the browser. After an update the app is
 * closed by Android, so a notification offers to open the new version.
 */
public class SelfUpdate extends BroadcastReceiver {

    private static final String APK = "team-shuffler.apk";
    private static final String EXTRA_QUIET = "quiet";

    /** Update button: asks once for "install unknown apps", then downloads and installs. */
    static void start(Activity a) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            a.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putBoolean("resumeInstall", true).apply();
            Toast.makeText(a, "Allow Team Shuffler to install updates, then come back", Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                install(a, false);
            }
            return;
        }
        Toast.makeText(a, "Downloading the update…", Toast.LENGTH_SHORT).show();
        install(a, true);
    }

    /** From onResume: continues after the user allowed installs in Settings. */
    static void resume(Activity a) {
        android.content.SharedPreferences p = a.getSharedPreferences("update", Context.MODE_PRIVATE);
        if (!p.getBoolean("resumeInstall", false)) return;
        p.edit().remove("resumeInstall").apply();
        if (a.getPackageManager().canRequestPackageInstalls()) start(a);
    }

    static void install(Context ctx, boolean quiet) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                byte[] apk = Ota.get("https://github.com/" + BuildConfig.UPDATE_REPO + "/releases/latest/download/" + APK, null);
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                if (quiet && Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (OutputStream out = session.openWrite("update.apk", 0, apk.length)) {
                        out.write(apk);
                        session.fsync(out);
                    }
                    app.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putBoolean("selfUpdating", true).apply();
                    Intent result = new Intent(app, SelfUpdate.class).putExtra(EXTRA_QUIET, quiet);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    session.commit(PendingIntent.getBroadcast(app, id, result, flags).getIntentSender());
                }
            } catch (Exception e) {
                toast(app, "Could not download the update. Are you online?");
            }
        }).start();
    }

    private static void toast(Context ctx, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            notifyUpdated(ctx);
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            ctx.getSharedPreferences("update", Context.MODE_PRIVATE).edit().remove("selfUpdating").apply();
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return;
            if (intent.getBooleanExtra(EXTRA_QUIET, false)) {
                // This phone refused the quiet install: same download with Android's install screen.
                Toast.makeText(ctx, "Opening the installer…", Toast.LENGTH_SHORT).show();
                install(ctx, false);
            } else {
                Toast.makeText(ctx, "The update failed. Try again later.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static void notifyUpdated(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("update", Context.MODE_PRIVATE);
        if (!p.getBoolean("selfUpdating", false)) return;
        p.edit().remove("selfUpdating").apply();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        nm.createNotificationChannel(new NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_LOW));
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (launch == null) return;
        PendingIntent open = PendingIntent.getActivity(ctx, 5, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(77, new Notification.Builder(ctx, "updates")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Team Shuffler is updated")
                .setContentText("Tap to open the new version.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }
}
