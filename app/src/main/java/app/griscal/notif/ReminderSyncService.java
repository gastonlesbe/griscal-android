package app.griscal.notif;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Background service that loads medications + appointments from Firestore
 * and schedules local AlarmManager notifications. Called after login and on boot.
 */
public class ReminderSyncService extends IntentService {

    public static final String CHANNEL_ID = "griscal_reminders";

    public ReminderSyncService() {
        super("ReminderSyncService");
    }

    public static void start(Context ctx) {
        ctx.startService(new Intent(ctx, ReminderSyncService.class));
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null) { Log.w("GriscalSync", "No user — skipping sync"); return; }
        Log.d("GriscalSync", "Starting sync for uid=" + uid);

        createNotificationChannel();

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        long now   = System.currentTimeMillis();
        long in7   = now + 7L * 24 * 60 * 60 * 1000;

        final List<ReminderItem> loaded = new ArrayList<>();
        final int[] pending = {2};
        final Object lock = new Object();

        // Medications
        db.collection("users").document(uid).collection("medications")
            .get()
            .addOnSuccessListener(snap -> {
                for (QueryDocumentSnapshot doc : snap) {
                    Boolean active = doc.getBoolean("active");
                    String startTime = doc.getString("startTime");
                    if (active == null || !active || startTime == null) continue;
                    Long endDate = doc.getLong("endDate");
                    if (endDate != null && endDate < now) continue;
                    long dueAt = computeNextDose(startTime, doc.getString("frequency"));
                    if (dueAt <= 0 || dueAt > in7) continue;
                    String name = doc.getString("name");
                    String dose = doc.getString("dose");
                    loaded.add(new ReminderItem("med_" + doc.getId(),
                        ReminderItem.Type.MEDICATION,
                        name != null ? name : "Medication",
                        dose != null ? dose : "", dueAt));
                }
                Log.d("GriscalSync", "Medications loaded: " + snap.size() + " docs, items added so far: " + loaded.size());
                synchronized (lock) {
                    pending[0]--;
                    if (pending[0] == 0) scheduleAlarms(loaded);
                }
            })
            .addOnFailureListener(e -> {
                Log.e("GriscalSync", "Medications query failed: " + e.getMessage());
                synchronized (lock) { pending[0]--; if (pending[0] == 0) scheduleAlarms(loaded); }
            });

        // Appointments
        db.collection("users").document(uid).collection("appointments")
            .get()
            .addOnSuccessListener(snap -> {
                for (QueryDocumentSnapshot doc : snap) {
                    if (Boolean.TRUE.equals(doc.getBoolean("realized"))) continue;
                    Long when = doc.getLong("when");
                    if (when == null || when < now || when > in7) continue;
                    String title    = doc.getString("title");
                    String location = doc.getString("location");
                    loaded.add(new ReminderItem("appt_" + doc.getId(),
                        ReminderItem.Type.APPOINTMENT,
                        title != null ? title : "Appointment",
                        location != null ? location : "", when));
                }
                Log.d("GriscalSync", "Appointments loaded: " + snap.size() + " docs, items added so far: " + loaded.size());
                synchronized (lock) {
                    pending[0]--;
                    if (pending[0] == 0) scheduleAlarms(loaded);
                }
            })
            .addOnFailureListener(e -> {
                Log.e("GriscalSync", "Appointments query failed: " + e.getMessage());
                synchronized (lock) { pending[0]--; if (pending[0] == 0) scheduleAlarms(loaded); }
            });

        // Wait for async Firestore queries to finish (max 15s)
        try { Thread.sleep(15000); } catch (InterruptedException ignored) {}
    }

    private void scheduleAlarms(List<ReminderItem> reminders) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Log.d("GriscalSync", "Scheduling " + reminders.size() + " alarms");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d("GriscalSync", "canScheduleExactAlarms=" + am.canScheduleExactAlarms());
        }
        for (ReminderItem r : reminders) {
            Intent intent = new Intent(this, NotificationReceiver.class);
            intent.putExtra("title", r.type == ReminderItem.Type.MEDICATION ? "💊 " + r.title : "📅 " + r.title);
            intent.putExtra("body",  r.subtitle.isEmpty() ? "Time for your reminder" : r.subtitle);
            intent.putExtra("id",    r.id.hashCode());
            PendingIntent pi = PendingIntent.getBroadcast(this, r.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueAt, pi);
            } catch (SecurityException e) {
                am.set(AlarmManager.RTC_WAKEUP, r.dueAt, pi);
            }
        }
    }

    private long computeNextDose(String startTime, String frequency) {
        try {
            String[] parts = startTime.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            Calendar base = Calendar.getInstance();
            base.set(Calendar.HOUR_OF_DAY, h);
            base.set(Calendar.MINUTE, m);
            base.set(Calendar.SECOND, 0);
            base.set(Calendar.MILLISECOND, 0);
            long now = System.currentTimeMillis();
            if (frequency == null || frequency.isEmpty()) {
                if (base.getTimeInMillis() <= now) base.add(Calendar.DAY_OF_YEAR, 1);
                return base.getTimeInMillis();
            }
            String[] f = frequency.split(" ");
            int amount = Integer.parseInt(f[0]);
            String unit = f.length > 1 ? f[1] : "hours";
            long intervalMs = unit.startsWith("hour") ? (long) amount * 3600_000
                : unit.startsWith("day") ? (long) amount * 86_400_000
                : unit.startsWith("month") ? (long) amount * 30 * 86_400_000L
                : (long) amount * 365 * 86_400_000L;
            long next = base.getTimeInMillis();
            while (next <= now) next += intervalMs;
            return next;
        } catch (Exception e) { return 0; }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Griscal Reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Medication and appointment reminders");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
