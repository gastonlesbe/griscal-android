package app.griscal.notif;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
import java.util.List;
import java.util.Map;

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

        long now   = System.currentTimeMillis();
        long in7   = now + 7L * 24 * 60 * 60 * 1000;

        final List<ReminderItem> loaded = new ArrayList<>();
        final int[] pending = {2}; // wait for both queries

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
                    loaded.add(new ReminderItem(
                        "med_" + doc.getId(),
                        ReminderItem.Type.MEDICATION,
                        name != null ? name : "Medication",
                        dose != null ? dose : "",
                        dueAt
                    ));
                }
                checkDone(pending, loaded, now);
            })
            .addOnFailureListener(e -> checkDone(pending, loaded, now));

        // Appointments
        db.collection("users").document(uid).collection("appointments")
            .get()
            .addOnSuccessListener(snap -> {
                for (QueryDocumentSnapshot doc : snap) {
                    Boolean realized = doc.getBoolean("realized");
                    if (Boolean.TRUE.equals(realized)) continue;

                    Long when = doc.getLong("when");
                    if (when == null || when < now || when > in7) continue;

                    String title    = doc.getString("title");
                    String location = doc.getString("location");
                    loaded.add(new ReminderItem(
                        "appt_" + doc.getId(),
                        ReminderItem.Type.APPOINTMENT,
                        title != null ? title : "Appointment",
                        location != null ? location : "",
                        when
                    ));
                }
                checkDone(pending, loaded, now);
            })
            .addOnFailureListener(e -> checkDone(pending, loaded, now));
    }

    private void checkDone(int[] pending, List<ReminderItem> loaded, long now) {
        pending[0]--;
        if (pending[0] > 0) return;

        Collections.sort(loaded, (a, b) -> Long.compare(a.dueAt, b.dueAt));
        runOnUiThread(() -> {
            items.clear();
            items.addAll(loaded);
            adapter.notifyDataSetChanged();
            binding.progressBar.setVisibility(View.GONE);
            binding.swipeRefresh.setRefreshing(false);
            binding.tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            scheduleAlarms(loaded);
        });
    }

    // ── Next dose computation ─────────────────────────────────────────────────

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

            long intervalMs;
            if (unit.startsWith("hour"))  intervalMs = (long) amount * 3600_000;
            else if (unit.startsWith("day")) intervalMs = (long) amount * 86_400_000;
            else if (unit.startsWith("month")) intervalMs = (long) amount * 30 * 86_400_000L;
            else intervalMs = (long) amount * 365 * 86_400_000L;

            long next = base.getTimeInMillis();
            while (next <= now) next += intervalMs;
            return next;
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

    // ── Menu ──────────────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_logout) {
            signOut();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void signOut() {
        auth.signOut();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
