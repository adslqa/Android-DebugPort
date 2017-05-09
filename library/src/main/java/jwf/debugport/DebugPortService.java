package jwf.debugport;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import jwf.debugport.internal.debug.DebugTelnetServer;
import jwf.debugport.internal.TelnetServer;
import jwf.debugport.internal.Utils;
import jwf.debugport.internal.sqlite.SQLiteTelnetServer;

/**
 *
 */
public class DebugPortService extends Service {
    private static final String TAG = "DebugPortService";
    public static final String METADATA_DEBUG_PORT = "jwf.debugport.METADATA_DEBUG_PORT";
    public static final String METADATA_SQLITE_PORT = "jwf.debugport.METADATA_SQLITE_PORT";
    public static final String METADATA_STARTUP_COMMANDS = "jwf.debugport.METADATA_STARTUP_COMMANDS";
    private static final String INTENT_EXTRA_PARAMS = "jwf.debugport.PARAMS";
    private static final String ACTION_KILL = "jwf.debugport.ACTION_KILL";
    private static final String ACTION_STOP = "jwf.debugport.ACTION_STOP";
    private static final String ACTION_START = "jwf.debugport.ACTION_START";
    private static final String ACTION_INITIALIZE = "jwf.debugport.ACTION_INIT";
    private static final int STOP_REQUEST_CODE = 0;
    private static final int START_REQUEST_CODE = 1;
    private static final int NOTIFICATION_ID = R.id.debugport_notification_id;
    private TelnetServer mDebugServer;
    private PowerManager.WakeLock mWakeLock;
    private SQLiteTelnetServer mSQLiteServer;
    private Params mParams;
    private boolean mServersStarted;

    /**
     * Utility method to start the DebugPortService
     * @return Params object generated by looking at manifest metadata.
     */
    public static Params start(Context context) {
        Params params = getManifestParams(context);
        start(context, params);
        return params;
    }

    /**
     * Utility method to start the DebugPortService.
     * @param params Parameters to configure the service.
     */
    public static void start(Context context, @NonNull Params params) {
        Intent intent = new Intent(context, DebugPortService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(INTENT_EXTRA_PARAMS, params);
        context.startService(intent);
    }

    /**
     * Stop the currently-running server.
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, DebugPortService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    /**
     * Kill the service.
     */
    public static void kill(Context context) {
        Intent intent = new Intent(context, DebugPortService.class);
        context.stopService(intent);
    }

    private void stopServers() {
        if (mDebugServer != null) {
            mDebugServer.killServer();
            mDebugServer = null;
        }
        if (mSQLiteServer != null) {
            mSQLiteServer.killServer();
            mSQLiteServer = null;
        }
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
        mServersStarted = false;
        showNotification(false);
    }

    private void startServers(Params params) {
        if (mServersStarted) {
            stopServers();
        }

        mParams = params;
        mServersStarted = true;

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DebugPortWakeLock");
        mWakeLock.acquire();

        new AsyncTask<Params, Void, Void>() {
            @SuppressWarnings("deprecation")
            @Override
            protected void onPostExecute(Void res) {
                showNotification(true);
            }

            @Override
            protected Void doInBackground(Params... params) {
                try {
                    mDebugServer = new DebugTelnetServer(DebugPortService.this, params[0]);
                    mDebugServer.startServer();
                    mSQLiteServer = new SQLiteTelnetServer(DebugPortService.this, params[0]);
                    mSQLiteServer.startServer();
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }
        }.execute(params);
    }

    private void showNotification(boolean running) {
        startForeground(NOTIFICATION_ID, buildNotification(running, mParams));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_STOP.equals(intent.getAction())) {
            stopServers();
        } else if (ACTION_START.equals(intent.getAction())) {
            Params params = intent.getParcelableExtra(INTENT_EXTRA_PARAMS);
            startServers(params);
        } else if (ACTION_INITIALIZE.equals(intent.getAction())) {
            showNotification(false);
        } else if (ACTION_KILL.equals(intent.getAction())) {
            kill(this);
            return START_NOT_STICKY;
        } else {
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopServers();
        stopForeground(true);
    }

    private Notification buildNotification(boolean running, @Nullable Params params) {
        if (params == null) {
            params = getManifestParams(this);
        }

        String ip = Utils.getIpAddress(this);
        String message;
        String summary;
        if (running) {
            message = getString(R.string.debugport_notification_subtitle_running, ip, params.getDebugPort(), params.getSQLitePort());
            summary = getString(R.string.debugport_notification_summary_running, ip, params.getDebugPort(), params.getSQLitePort());
        } else {
            message = getString(R.string.debugport_notification_subtitle_stopped);
            summary = getString(R.string.debugport_notification_summary_stopped);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.drawable.debugport_ic_notification);
        builder.setContentTitle(getString(R.string.debugport_notification_title));
        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(message).setSummaryText(summary));
        builder.setPriority(NotificationCompat.PRIORITY_MIN);
        builder.setContentText(message);
        builder.addAction(running ? buildStopAction() : buildStartAction(params));
        builder.addAction(buildKillAction());

        return builder.build();
    }

    private NotificationCompat.Action buildKillAction() {
        Intent intent = new Intent(this, DebugPortService.class);
        intent.setAction(ACTION_KILL);

        PendingIntent pIntent = PendingIntent.getService(this, STOP_REQUEST_CODE, intent, 0);

        return new NotificationCompat.Action.Builder(0, getString(R.string.debugport_notification_action_kill), pIntent).build();
    }

    private NotificationCompat.Action buildStopAction() {
        Intent intent = new Intent(this, DebugPortService.class);
        intent.setAction(ACTION_STOP);

        PendingIntent pIntent = PendingIntent.getService(this, STOP_REQUEST_CODE, intent, 0);

        return new NotificationCompat.Action.Builder(0, getString(R.string.debugport_notification_action_stop), pIntent)
                .build();
    }

    private NotificationCompat.Action buildStartAction(@NonNull Params params) {
        Intent intent = new Intent(this, DebugPortService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(INTENT_EXTRA_PARAMS, params);

        PendingIntent pIntent = PendingIntent.getService(this, START_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Action.Builder(0, getString(R.string.debugport_notification_action_start), pIntent)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Called by {@link DebugPortContentProvider} to initialize the service.
     */
    static void initialize(Context context) {
        Intent intent = new Intent(context, DebugPortService.class);
        intent.setAction(ACTION_INITIALIZE);
        context.startService(intent);
    }

    /**
     * Load parameters from the manifest metadata.
     */
    private static Params getManifestParams(Context context) {
        Params params = new Params();

        try {
            Context app = context.getApplicationContext();
            ApplicationInfo ai = app.getPackageManager().getApplicationInfo(app.getPackageName(), PackageManager.GET_META_DATA);
            Bundle bundle = ai.metaData;

            params.setDebugPort(bundle.getInt(METADATA_DEBUG_PORT, params.getDebugPort()));
            params.setSQLitePort(bundle.getInt(METADATA_SQLITE_PORT, params.getSQLitePort()));

            int startupCommandsResId = bundle.getInt(METADATA_STARTUP_COMMANDS, 0);
            if (startupCommandsResId != 0) {
                try {
                    params.setStartupCommands(app.getResources().getStringArray(startupCommandsResId));
                } catch (Resources.NotFoundException nfe) {
                    Log.w(TAG, "Error getting startup commands. Using empty array of commands instead.", nfe);
                }
            }
        } catch (PackageManager.NameNotFoundException | NullPointerException e) {
            Log.w(TAG, "Error getting metadata, using default parameters.", e);
        }

        return params;
    }
}
