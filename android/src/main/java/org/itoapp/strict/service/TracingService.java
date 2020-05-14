package org.itoapp.strict.service;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.itoapp.DistanceCallback;
import org.itoapp.PublishUUIDsCallback;
import org.itoapp.TracingServiceInterface;
import org.itoapp.strict.Constants;
import org.itoapp.strict.Helper;
import org.itoapp.strict.Preconditions;
import org.itoapp.strict.database.ItoDBHelper;
import org.itoapp.strict.database.RoomDB;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import static org.itoapp.strict.Constants.RATCHET_EXCHANGE_INTERVAL;

public class TracingService extends Service {
    private static final String LOG_TAG = "ITOTracingService";
    private static final String DEFAULT_NOTIFICATION_CHANNEL = "ContactTracing";
    private static final int NOTIFICATION_ID = 1;
    private TCNProtoGen tcnProto;
    private SecureRandom uuidGenerator;
    private Looper serviceLooper;
    private Handler serviceHandler;
    private BleScanner bleScanner;
    private BleAdvertiser bleAdvertiser;
    private ContactCache contactCache;
    private ItoDBHelper dbHelper;

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            if (!isBluetoothRunning()) {
                startBluetooth();
            } else if (!Preconditions.canScanBluetooth(context)) {
                stopBluetooth();
            }
        }
    };

    private TracingServiceInterface.Stub binder = new TracingServiceInterface.Stub() {
        @Override
        public void setDistanceCallback(DistanceCallback distanceCallback) {
            contactCache.setDistanceCallback(distanceCallback);
        }


        @RequiresApi(api = 24)
        @Override
        public void publishBeaconUUIDs(long from, long to, PublishUUIDsCallback callback) {
            // todo use from & to ?
            Log.d(LOG_TAG, "Publishing Reports...");
            List<byte[]> reports = TCNProtoUtil.loadAllRatchets().stream().map(ratchet -> ratchet.generateReport(ratchet.getRatchetTickCount())).collect(Collectors.toList());

            new PublishBeaconsTask(reports, callback).execute();
        }

        @RequiresApi(api = 24)
        @Override
        public boolean isPossiblyInfected() {
            //TODO do async
            Long totalExposureDuration = RoomDB.db.seenTCNDao().findSickTCNs().stream().map(x -> x.duration).reduce(0L, (a, b) -> a + b);
            return totalExposureDuration > Constants.MIN_EXPOSURE_DURATION;
        }

        @Override
        public void restartTracingService() {
            stopBluetooth();
            startBluetooth();
        }

        @Override
        public int getLatestFetchTime() {
            return dbHelper.getLatestFetchTime();
        }
    };

    private Runnable regenerateUUID = () -> {
        Log.i(LOG_TAG, "Regenerating TCN");

      /*  byte[] uuid = new byte[Constants.UUID_LENGTH];
        uuidGenerator.nextBytes(uuid);
        byte[] hashedUUID = Helper.calculateTruncatedSHA256(uuid);

        dbHelper.insertBeacon(uuid);

        byte[] broadcastData = new byte[Constants.BROADCAST_LENGTH];
        broadcastData[Constants.BROADCAST_LENGTH - 1] = getTransmitPower();
        System.arraycopy(hashedUUID, 0, broadcastData, 0, Constants.HASH_LENGTH);
*/


        if (tcnProto != null && tcnProto.currentTCKpos == RATCHET_EXCHANGE_INTERVAL) {
            tcnProto = null;
        }
        if (tcnProto == null) {
            Log.i(LOG_TAG, "Regenerating Ratchet");
            tcnProto = new TCNProtoGen();
        }
        byte[] tcn = tcnProto.getNewTCN();
        Log.i(LOG_TAG, "Advertising " + Helper.byte2Hex(tcn));
        bleAdvertiser.setBroadcastData(tcn);


        AsyncTask.execute(new Runnable() { // FIXME make everything async and get aligned with sendReport etc.
            @Override
            public void run() {
                TCNProtoUtil.persistRatchet(tcnProto);
            }
        });

        serviceHandler.postDelayed(this.regenerateUUID, Constants.TCN_VALID_INTERVAL);
    };
    //TODO move this to some alarmManager governed section.
// Also ideally check the server when connected to WIFI and charger
    private Runnable checkServer = () -> {
        new CheckServerTask(dbHelper).execute();
        serviceHandler.postDelayed(this.checkServer, Constants.CHECK_SERVER_INTERVAL);
    };


    private boolean isBluetoothRunning() {
        return bleScanner != null;
    }

    private void stopBluetooth() {
        Log.i(LOG_TAG, "Stopping Bluetooth");
        contactCache.flush();
        if (bleScanner != null)
            try {
                bleScanner.stopScanning();
            } catch (Exception ignored) {
            }
        if (bleAdvertiser != null)
            try {
                bleAdvertiser.stopAdvertising();
            } catch (Exception ignored) {
            }

        serviceHandler.removeCallbacks(regenerateUUID);

        bleScanner = null;
        bleAdvertiser = null;
    }

    private void startBluetooth() {
        Log.i(LOG_TAG, "Starting Bluetooth");
        if (!Preconditions.canScanBluetooth(this)) {
            Log.w(LOG_TAG, "Preconditions for starting Bluetooth not met");
            return;
        }
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        assert bluetoothManager != null;
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        bleScanner = new BleScanner(bluetoothAdapter, contactCache);
        bleAdvertiser = new BleAdvertiser(bluetoothAdapter, serviceHandler);

        regenerateUUID.run();
        bleAdvertiser.startAdvertising();
        bleScanner.startScanning();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        uuidGenerator = new SecureRandom();
        dbHelper = new ItoDBHelper();
        HandlerThread thread = new HandlerThread("TracingServiceHandler", Thread.NORM_PRIORITY);
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        serviceLooper = thread.getLooper();
        serviceHandler = new Handler(serviceLooper);
        serviceHandler.post(this.checkServer);
        contactCache = new ContactCache(dbHelper, serviceHandler);

        startBluetooth();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        registerReceiver(broadcastReceiver, filter);
    }

    @TargetApi(26)
    private void createNotificationChannel(NotificationManager notificationManager) {
        int importance = NotificationManager.IMPORTANCE_DEFAULT;

        NotificationChannel mChannel = new NotificationChannel(DEFAULT_NOTIFICATION_CHANNEL, DEFAULT_NOTIFICATION_CHANNEL, importance);
        mChannel.enableLights(true);
        mChannel.setLightColor(Color.BLUE);
        mChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(mChannel);
    }

    private void runAsForgroundService() {
        NotificationManager notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            createNotificationChannel(notificationManager);

        Intent notificationIntent = new Intent();

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, 0);

        Notification notification = new NotificationCompat.Builder(this,
                DEFAULT_NOTIFICATION_CHANNEL)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationManager.IMPORTANCE_LOW)
                .setVibrate(null)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        bleAdvertiser.stopAdvertising();
        bleScanner.stopScanning();
        contactCache.flush();
        unregisterReceiver(broadcastReceiver);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        runAsForgroundService();
        return START_STICKY;
    }

    /*
    Don't do anything here, because the service doesn't have to communicate to other apps
     */
    @Override
    public IBinder onBind(Intent intent) {
        return (IBinder) binder;
    }
}
