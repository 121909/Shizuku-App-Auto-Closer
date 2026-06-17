package com.example.lockguard;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.lockguard.data.TargetApp;

import java.util.ArrayList;
import java.util.List;

public final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
    public interface Listener {
        void onAppSelected(TargetApp app);
    }

    private final ArrayList<TargetApp> items = new ArrayList<>();
    private final Listener listener;
    private String selectedPackage;

    public AppAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<TargetApp> apps, String selectedPackage) {
        items.clear();
        items.addAll(apps);
        this.selectedPackage = selectedPackage;
        notifyDataSetChanged();
    }

    public void setSelectedPackage(String selectedPackage) {
        this.selectedPackage = selectedPackage;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        TargetApp app = items.get(position);
        holder.title.setText(app.label);
        holder.subtitle.setText(app.packageName);
        boolean selected = app.packageName.equals(selectedPackage);
        holder.radio.setChecked(selected);
        holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.getContext(),
                selected ? R.color.item_selected : R.color.item_normal));
        holder.itemView.setOnClickListener(v -> listener.onAppSelected(app));
        holder.radio.setOnClickListener(v -> listener.onAppSelected(app));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final RadioButton radio;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.appTitle);
            subtitle = itemView.findViewById(R.id.appPackage);
            radio = itemView.findViewById(R.id.appRadio);
        }
    }
}

