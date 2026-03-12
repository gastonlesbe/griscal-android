package app.griscal.notif;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReminderAdapter extends RecyclerView.Adapter<ReminderAdapter.VH> {

    private final List<ReminderItem> items;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("EEE dd MMM · HH:mm", Locale.getDefault());

    public ReminderAdapter(List<ReminderItem> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reminder, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ReminderItem item = items.get(position);
        holder.tvTitle.setText(item.title);
        holder.tvSubtitle.setText(item.subtitle.isEmpty()
                ? FMT.format(new Date(item.dueAt))
                : FMT.format(new Date(item.dueAt)) + "  ·  " + item.subtitle);
        holder.ivIcon.setImageResource(
                item.type == ReminderItem.Type.MEDICATION ? R.drawable.ic_bell : R.drawable.ic_calendar
        );
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvTitle, tvSubtitle;
        VH(View v) {
            super(v);
            ivIcon     = v.findViewById(R.id.ivIcon);
            tvTitle    = v.findViewById(R.id.tvTitle);
            tvSubtitle = v.findViewById(R.id.tvSubtitle);
        }
    }
}
