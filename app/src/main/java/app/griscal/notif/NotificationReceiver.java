package app.griscal.notif;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

public class NotificationReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "griscal_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        // On device boot: re-sync reminders from Firestore so alarms are rescheduled
        if (android.content.Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ReminderSyncService.start(context);
            return;
        }

        String title = intent.getStringExtra("title");
        String body  = intent.getStringExtra("body");
        int    id    = intent.getIntExtra("id", 0);

        if (title == null) return;

        Intent openIntent = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, builder.build());
    }
}
