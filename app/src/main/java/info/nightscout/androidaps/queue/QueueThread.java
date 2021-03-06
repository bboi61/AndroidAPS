package info.nightscout.androidaps.queue;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.events.EventPumpStatusChanged;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.Overview.events.EventDismissBolusprogressIfRunning;
import info.nightscout.androidaps.queue.events.EventQueueChanged;
import info.nightscout.utils.SP;

/**
 * Created by mike on 09.11.2017.
 */

public class QueueThread extends Thread {
    private static Logger log = LoggerFactory.getLogger(QueueThread.class);

    private CommandQueue queue;

    private boolean connectLogged = false;

    private PowerManager.WakeLock mWakeLock;

    public QueueThread(CommandQueue queue) {
        super(QueueThread.class.toString());

        this.queue = queue;
        PowerManager powerManager = (PowerManager) MainApp.instance().getApplicationContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "QueueThread");
    }

    @Override
    public final void run() {
        mWakeLock.acquire();
        MainApp.bus().post(new EventQueueChanged());
        long connectionStartTime = System.currentTimeMillis();

        try {
            while (true) {
                PumpInterface pump = ConfigBuilderPlugin.getActivePump();
                long secondsElapsed = (System.currentTimeMillis() - connectionStartTime) / 1000;
                if (pump.isConnecting()) {
                    log.debug("QUEUE: connecting " + secondsElapsed);
                    MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.CONNECTING, (int) secondsElapsed));
                    SystemClock.sleep(1000);
                    continue;
                }

                if (!pump.isConnected() && secondsElapsed > Constants.PUMP_MAX_CONNECTION_TIME_IN_SECONDS) {
                    MainApp.bus().post(new EventDismissBolusprogressIfRunning(new PumpEnactResult()));
                    MainApp.bus().post(new EventPumpStatusChanged(MainApp.sResources.getString(R.string.connectiontimedout)));
                    log.debug("QUEUE: timed out");
                    pump.stopConnecting();

                    //BLUETOOTH-WATCHDOG
                    boolean watchdog = SP.getBoolean(R.string.key_btwatchdog, false);
                    long last_watchdog = SP.getLong(R.string.key_btwatchdog_lastbark, 0l);
                    watchdog = watchdog && System.currentTimeMillis() - last_watchdog > (Constants.MIN_WATCHDOG_INTERVAL_IN_SECONDS * 1000);
                    if(watchdog) {
                        log.debug("BT watchdog - toggeling the phonest bluetooth");
                        //write time
                        SP.putLong(R.string.key_btwatchdog_lastbark, System.currentTimeMillis());
                        //toggle BT
                        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                        mBluetoothAdapter.disable();
                        SystemClock.sleep(1000);
                        mBluetoothAdapter.enable();
                        SystemClock.sleep(1000);
                        //start over again once after watchdog barked
                        connectionStartTime = System.currentTimeMillis();
                    } else {
                        queue.clear();
                        return;
                    }
                }

                if (!pump.isConnected()) {
                    log.debug("QUEUE: connect");
                    MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.CONNECTING, (int) secondsElapsed));
                    pump.connect("Connection needed");
                    SystemClock.sleep(1000);
                    continue;
                }

                if (queue.performing() == null) {
                    if (!connectLogged) {
                        connectLogged = true;
                        log.debug("QUEUE: connection time " + secondsElapsed + "s");
                    }
                    // Pickup 1st command and set performing variable
                    if (queue.size() > 0) {
                        queue.pickup();
                        log.debug("QUEUE: performing " + queue.performing().status());
                        MainApp.bus().post(new EventQueueChanged());
                        queue.performing().execute();
                        queue.resetPerforming();
                        MainApp.bus().post(new EventQueueChanged());
                        SystemClock.sleep(100);
                        continue;
                    }
                }

                if (queue.size() == 0 && queue.performing() == null) {
                    log.debug("QUEUE: queue empty. disconnect");
                    MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTING));
                    pump.disconnect("Queue empty");
                    MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTED));
                    return;
                }
            }
        } finally {
            mWakeLock.release();
        }
    }


}
