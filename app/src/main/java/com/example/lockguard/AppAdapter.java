package com.example.lockguard;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.lockguard.data.TargetApp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
    public interface Listener {
        void onAppSelectionChanged(TargetApp app, boolean selected);
    }

    private final ArrayList<TargetApp> items = new ArrayList<>();
    private final ArrayList<TargetApp> allItems = new ArrayList<>();
    private final Listener listener;
    private final HashSet<String> selectedPackages = new HashSet<>();

    public AppAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<TargetApp> apps, Collection<String> selectedPackages) {
        allItems.clear();
        allItems.addAll(apps);
        setSelectedPackagesInternal(selectedPackages);
        rebuildItems();
        notifyDataSetChanged();
    }

    public void setSelectedPackages(Collection<String> selectedPackages) {
        setSelectedPackagesInternal(selectedPackages);
        rebuildItems();
        notifyDataSetChanged();
    }

    private void setSelectedPackagesInternal(Collection<String> packages) {
        selectedPackages.clear();
        if (packages == null) {
            return;
        }
        for (String packageName : packages) {
            if (packageName != null && !packageName.isEmpty()) {
                selectedPackages.add(packageName);
            }
        }
    }

    private void rebuildItems() {
        items.clear();
        items.addAll(allItems);
        items.sort(Comparator
                .comparing((TargetApp app) -> !selectedPackages.contains(app.packageName))
                .thenComparing(app -> app.label.toLowerCase(Locale.getDefault())));
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
        holder.icon.setImageDrawable(app.icon);
        holder.title.setText(app.label);
        holder.subtitle.setText(app.packageName);
        boolean selected = selectedPackages.contains(app.packageName);
        holder.checkBox.setChecked(selected);
        holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.getContext(),
                selected ? R.color.item_selected : R.color.item_normal));
        View.OnClickListener clickListener = v -> listener.onAppSelectionChanged(app, !selectedPackages.contains(app.packageName));
        holder.itemView.setOnClickListener(clickListener);
        holder.checkBox.setOnClickListener(clickListener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final CheckBox checkBox;

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.appIcon);
            title = itemView.findViewById(R.id.appTitle);
            subtitle = itemView.findViewById(R.id.appPackage);
            checkBox = itemView.findViewById(R.id.appCheckBox);
        }
    }
}
