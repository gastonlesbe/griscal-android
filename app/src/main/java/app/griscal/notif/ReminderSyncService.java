package app.griscal.notif;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background service that loads medications + appointments from Firestore
 * and schedules local AlarmManager notifications. Called after login and on boot.
 */
public class ReminderSyncService extends Service {

    public static final String CHANNEL_ID = "griscal_reminders";

    public static void start(Context ctx) {
        ctx.startService(new Intent(ctx, ReminderSyncService.class));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            doSync();
            stopSelf(startId);
        }).start();
        return START_NOT_STICKY;
    }

    private void doSync() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null) { Log.w("GriscalSync", "No user — skipping sync"); return; }
        Log.d("GriscalSync", "Starting sync for uid=" + uid);

        createNotificationChannel();

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        long now = System.currentTimeMillis();
        long in7 = now + 7L * 24 * 60 * 60 * 1000;

        final Object done = new Object();
        final boolean[] finished = {false};

        // Step 1: get all subject refs for this user
        db.collection("users").document(uid).collection("subjectRefs").get()
            .addOnSuccessListener(refsSnap -> {
                List<String> subjectIds = new ArrayList<>();
                Map<String, String> subjectNames = new HashMap<>();
                for (QueryDocumentSnapshot ref : refsSnap) {
                    String sid   = ref.getString("subjectId");
                    String sname = ref.getString("subjectName");
                    if (sid != null) {
                        subjectIds.add(sid);
                        subjectNames.put(sid, sname != null ? sname : "");
                    }
                }
                Log.d("GriscalSync", "Found " + subjectIds.size() + " subjects");

                if (subjectIds.isEmpty()) {
                    scheduleAlarms(new ArrayList<>());
                    synchronized (done) { finished[0] = true; done.notifyAll(); }
                    return;
                }

                // Step 2: query medications + appointments for each subject
                List<ReminderItem> loaded = new ArrayList<>();
                Object lock = new Object();
                AtomicInteger pending = new AtomicInteger(subjectIds.size() * 2);

                for (String subjectId : subjectIds) {
                    String subjectName = subjectNames.getOrDefault(subjectId, "");

                    db.collection("subjects").document(subjectId).collection("medications").get()
                        .addOnCompleteListener(task -> {
                            if (task.isSuccessful()) {
                                for (QueryDocumentSnapshot doc : task.getResult()) {
                                    Boolean active = doc.getBoolean("active");
                                    String startTime = doc.getString("startTime");
                                    if (active == null || !active || startTime == null) continue;
                                    Long endDate = doc.getLong("endDate");
                                    if (endDate != null && endDate < now) continue;
                                    long dueAt = computeNextDose(startTime, doc.getLong("startDate"), doc.getString("frequency"));
                                    if (dueAt <= 0 || dueAt > in7) continue;
                                    String name = doc.getString("name");
                                    String dose = doc.getString("dose");
                                    synchronized (lock) {
                                        loaded.add(new ReminderItem("med_" + doc.getId(),
                                            ReminderItem.Type.MEDICATION,
                                            name != null ? name : "Medication",
                                            buildSubtitle(subjectName, dose), dueAt));
                                    }
                                }
                            } else {
                                Log.e("GriscalSync", "Meds failed for " + subjectId + ": " + task.getException());
                            }
                            if (pending.decrementAndGet() == 0) {
                                scheduleAlarms(loaded);
                                synchronized (done) { finished[0] = true; done.notifyAll(); }
                            }
                        });

                    db.collection("subjects").document(subjectId).collection("appointments").get()
                        .addOnCompleteListener(task -> {
                            if (task.isSuccessful()) {
                                for (QueryDocumentSnapshot doc : task.getResult()) {
                                    if (Boolean.TRUE.equals(doc.getBoolean("realized"))) continue;
                                    Long when = doc.getLong("when");
                                    if (when == null || when < now || when > in7) continue;
                                    String title    = doc.getString("title");
                                    String location = doc.getString("location");
                                    synchronized (lock) {
                                        loaded.add(new ReminderItem("appt_" + doc.getId(),
                                            ReminderItem.Type.APPOINTMENT,
                                            title != null ? title : "Appointment",
                                            buildSubtitle(subjectName, location), when));
                                    }
                                }
                            } else {
                                Log.e("GriscalSync", "Appts failed for " + subjectId + ": " + task.getException());
                            }
                            if (pending.decrementAndGet() == 0) {
                                scheduleAlarms(loaded);
                                synchronized (done) { finished[0] = true; done.notifyAll(); }
                            }
                        });
                }
            })
            .addOnFailureListener(e -> {
                Log.e("GriscalSync", "subjectRefs failed: " + e.getMessage());
                synchronized (done) { finished[0] = true; done.notifyAll(); }
            });

        // Wait up to 10s for Firestore callbacks
        synchronized (done) {
            if (!finished[0]) {
                try { done.wait(10000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private String buildSubtitle(String subjectName, String detail) {
        boolean hasName   = subjectName != null && !subjectName.isEmpty();
        boolean hasDetail = detail != null && !detail.isEmpty();
        if (hasName && hasDetail) return subjectName + " · " + detail;
        if (hasName)   return subjectName;
        if (hasDetail) return detail;
        return "";
    }

    private void scheduleAlarms(List<ReminderItem> reminders) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Log.d("GriscalSync", "Scheduling " + reminders.size() + " alarms");
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

    private long computeNextDose(String startTime, Long startDate, String frequency) {
        try {
            String[] parts = startTime.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            long now = System.currentTimeMillis();

            String unit = "hours";
            int amount = 1;
            if (frequency != null && !frequency.isEmpty()) {
                String[] f = frequency.split(" ");
                amount = Integer.parseInt(f[0]);
                unit = f.length > 1 ? f[1] : "hours";
            }

            if ((unit.startsWith("month") || unit.startsWith("year")) && startDate == null) return 0;

            Calendar base = Calendar.getInstance();
            if (startDate != null) base.setTimeInMillis(startDate);
            base.set(Calendar.HOUR_OF_DAY, h);
            base.set(Calendar.MINUTE, m);
            base.set(Calendar.SECOND, 0);
            base.set(Calendar.MILLISECOND, 0);

            if (frequency == null || frequency.isEmpty()) {
                return base.getTimeInMillis() >= now ? base.getTimeInMillis() : 0;
            }

            if (unit.startsWith("hour")) {
                long intervalMs = (long) amount * 3600_000;
                long next = base.getTimeInMillis();
                while (next < now) next += intervalMs;
                return next;
            } else if (unit.startsWith("day")) {
                while (base.getTimeInMillis() <= now) base.add(Calendar.DAY_OF_YEAR, amount);
            } else if (unit.startsWith("month")) {
                while (base.getTimeInMillis() <= now) base.add(Calendar.MONTH, amount);
            } else {
                while (base.getTimeInMillis() <= now) base.add(Calendar.YEAR, amount);
            }
            return base.getTimeInMillis();
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
