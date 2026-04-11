package app.griscal.notif;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import app.griscal.notif.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "griscal_reminders";
    private static final int REQ_NOTIF = 100;

    private ActivityMainBinding binding;
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private ReminderAdapter adapter;
    private final List<ReminderItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();

        createNotificationChannel();
        requestNotificationPermission();
        requestExactAlarmPermission();
        registerFcmToken();

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReminderAdapter(items);
        binding.recyclerView.setAdapter(adapter);

        binding.swipeRefresh.setOnRefreshListener(this::loadReminders);

        loadReminders();
    }

    // ── Load upcoming medications + appointments ───────────────────────────────

    private void loadReminders() {
        String uid = auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
        if (uid == null) { signOut(); return; }

        binding.progressBar.setVisibility(View.VISIBLE);
        binding.tvEmpty.setVisibility(View.GONE);

        long now = System.currentTimeMillis();
        long in7 = now + 7L * 24 * 60 * 60 * 1000;

        // Step 1: get all subject refs for this user
        db.collection("users").document(uid).collection("subjectRefs").get()
            .addOnSuccessListener(refsSnap -> {
                List<String> subjectIds = new ArrayList<>();
                Map<String, String> subjectNames = new HashMap<>();
                for (QueryDocumentSnapshot ref : refsSnap) {
                    String sid = ref.getString("subjectId");
                    String sname = ref.getString("subjectName");
                    if (sid != null) {
                        subjectIds.add(sid);
                        subjectNames.put(sid, sname != null ? sname : "");
                    }
                }
                if (subjectIds.isEmpty()) {
                    showResults(new ArrayList<>());
                    return;
                }
                // Step 2: query medications + appointments for each subject
                loadForSubjects(subjectIds, subjectNames, now, in7);
            })
            .addOnFailureListener(e -> {
                Log.e("GriscalMain", "subjectRefs failed: " + e.getMessage());
                showResults(new ArrayList<>());
            });
    }

    private void loadForSubjects(List<String> subjectIds, Map<String, String> subjectNames,
                                  long now, long in7) {
        List<ReminderItem> loaded = new ArrayList<>();
        AtomicInteger pending = new AtomicInteger(subjectIds.size() * 2);

        for (String subjectId : subjectIds) {
            String subjectName = subjectNames.getOrDefault(subjectId, "");

            // Medications
            db.collection("subjects").document(subjectId).collection("medications").get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        for (QueryDocumentSnapshot doc : task.getResult()) {
                            Boolean active = doc.getBoolean("active");
                            String startTime = doc.getString("startTime");
                            if (active == null || !active || startTime == null) continue;
                            Long endDate = doc.getLong("endDate");
                            if (endDate != null && endDate < now) continue;
                            long dueAt = computeNextDose(startTime, doc.getString("frequency"), doc.getLong("startDate"));
                            if (dueAt <= 0 || dueAt > in7) continue;
                            String name = doc.getString("name");
                            String dose = doc.getString("dose");
                            synchronized (loaded) {
                                loaded.add(new ReminderItem("med_" + doc.getId(),
                                    ReminderItem.Type.MEDICATION,
                                    name != null ? name : "Medication",
                                    buildSubtitle(subjectName, dose), dueAt));
                            }
                        }
                    } else {
                        Log.e("GriscalMain", "Meds failed for " + subjectId + ": " + task.getException());
                    }
                    if (pending.decrementAndGet() == 0) runOnUiThread(() -> showResults(loaded));
                });

            // Appointments
            db.collection("subjects").document(subjectId).collection("appointments").get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        for (QueryDocumentSnapshot doc : task.getResult()) {
                            if (Boolean.TRUE.equals(doc.getBoolean("realized"))) continue;
                            Long when = doc.getLong("when");
                            if (when == null || when < now || when > in7) continue;
                            String title    = doc.getString("title");
                            String location = doc.getString("location");
                            synchronized (loaded) {
                                loaded.add(new ReminderItem("appt_" + doc.getId(),
                                    ReminderItem.Type.APPOINTMENT,
                                    title != null ? title : "Appointment",
                                    buildSubtitle(subjectName, location), when));
                            }
                        }
                    } else {
                        Log.e("GriscalMain", "Appts failed for " + subjectId + ": " + task.getException());
                    }
                    if (pending.decrementAndGet() == 0) runOnUiThread(() -> showResults(loaded));
                });
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

    private void showResults(List<ReminderItem> loaded) {
        Collections.sort(loaded, (a, b) -> Long.compare(a.dueAt, b.dueAt));
        items.clear();
        items.addAll(loaded);
        adapter.notifyDataSetChanged();
        binding.progressBar.setVisibility(View.GONE);
        binding.swipeRefresh.setRefreshing(false);
        binding.tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        scheduleAlarms(loaded);
    }

    // ── Next dose computation ─────────────────────────────────────────────────

    private long computeNextDose(String startTime, String frequency, Long startDate) {
        try {
            String[] parts = startTime.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);

            long now = System.currentTimeMillis();

            Calendar base = Calendar.getInstance();
            if (startDate != null) base.setTimeInMillis(startDate);
            base.set(Calendar.HOUR_OF_DAY, h);
            base.set(Calendar.MINUTE, m);
            base.set(Calendar.SECOND, 0);
            base.set(Calendar.MILLISECOND, 0);

            if (frequency == null || frequency.isEmpty()) {
                return base.getTimeInMillis() >= now ? base.getTimeInMillis() : 0;
            }

            String[] f  = frequency.split(" ");
            int amount  = Integer.parseInt(f[0]);
            String unit = f.length > 1 ? f[1] : "hours";

            if ((unit.startsWith("month") || unit.startsWith("year")) && startDate == null) return 0;

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
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Schedule local alarms ─────────────────────────────────────────────────

    private void scheduleAlarms(List<ReminderItem> reminders) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        for (ReminderItem r : reminders) {
            Intent intent = new Intent(this, NotificationReceiver.class);
            intent.putExtra("title", r.type == ReminderItem.Type.MEDICATION ? "💊 " + r.title : "📅 " + r.title);
            intent.putExtra("body",  r.subtitle.isEmpty() ? "Time for your reminder" : r.subtitle);
            intent.putExtra("id",    r.id.hashCode());

            PendingIntent pi = PendingIntent.getBroadcast(
                this, r.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueAt, pi);
            } catch (SecurityException e) {
                am.set(AlarmManager.RTC_WAKEUP, r.dueAt, pi);
            }
        }
    }

    // ── FCM token ─────────────────────────────────────────────────────────────

    private void registerFcmToken() {
        String uid = auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
        if (uid == null) return;

        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (token == null) return;
            db.collection("users").document(uid)
              .collection("fcmTokens").document(token)
              .set(Map.of("token", token, "platform", "android",
                          "updatedAt", System.currentTimeMillis()));
        });
    }

    // ── Notification channel ──────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Griscal Reminders", NotificationManager.IMPORTANCE_HIGH
        );
        ch.setDescription("Medication and appointment reminders");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_logout) {
            signOut();
            return true;
        } else if (id == R.id.action_open_web) {
            openWebApp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openWebApp() {
        startActivity(new Intent(this, WebAppActivity.class));
    }

    private void signOut() {
        auth.signOut();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
