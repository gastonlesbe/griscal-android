package app.griscal.notif;

public class ReminderItem {
    public enum Type { MEDICATION, APPOINTMENT }

    public final String id;
    public final Type type;
    public final String title;
    public final String subtitle;
    public final long dueAt; // epoch ms

    public ReminderItem(String id, Type type, String title, String subtitle, long dueAt) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.subtitle = subtitle;
        this.dueAt = dueAt;
    }
}
