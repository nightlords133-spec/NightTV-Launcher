package ru.nightlord.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Срабатывание таймера сна. */
public class SleepReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (SleepTimer.ACTION_WARN.equals(action)) {
            Toast.makeText(context, "Таймер сна: через минуту экран выключится", Toast.LENGTH_LONG).show();
        } else if (SleepTimer.ACTION_SLEEP.equals(action)) {
            SleepTimer.sleepNow(context);
        }
    }
}
