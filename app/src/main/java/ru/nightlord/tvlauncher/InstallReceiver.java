package ru.nightlord.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Получает результат установки обновления. */
public class InstallReceiver extends BroadcastReceiver {

    @SuppressWarnings("deprecation")
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // Система просит подтвердить установку
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (Exception e) {
                        toast(context, "Не удалось открыть окно установки");
                    }
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                toast(context, "NightTV обновлён");
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                toast(context, "Обновление отменено");
                break;
            default:
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                toast(context, "Ошибка обновления" + (msg != null ? ": " + msg : ""));
                break;
        }
    }

    private static void toast(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_LONG).show();
    }
}
