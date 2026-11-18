package com.example.lockguard;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.lockguard.data.TargetApp;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
    private String searchQuery = "";
    private boolean showSystemApps = false;
    private boolean onlySelected = false;

    public AppAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<TargetApp> apps, Collection<String> selectedPackages) {
        allItems.clear();
        allItems.addAll(apps);
        setSelectedPackagesInternal(selectedPackages);
        rebuildItems(true);
        notifyDataSetChanged();
    }

    public void setSelectedPackages(Collection<String> selectedPackages, boolean resort) {
        setSelectedPackagesInternal(selectedPackages);
        if (resort || onlySelected) {
            rebuildItems(resort);
            notifyDataSetChanged();
        }
    }

    public void setFilter(String query, boolean showSystem, boolean onlySelected) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        if (normalized.equals(this.searchQuery)
                && showSystem == this.showSystemApps
                && onlySelected == this.onlySelected) {
            return;
        }
        this.searchQuery = normalized;
        this.showSystemApps = showSystem;
        this.onlySelected = onlySelected;
        rebuildItems(false);
        notifyDataSetChanged();
    }

    public List<TargetApp> getVisibleApps() {
        return Collections.unmodifiableList(new ArrayList<>(items));
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

    private void rebuildItems(boolean resortAll) {
        if (resortAll) {
            allItems.sort(Comparator
                    .comparing((TargetApp app) -> !selectedPackages.contains(app.packageName))
                    .thenComparing(app -> app.label.toLowerCase(Locale.getDefault())));
        }
        items.clear();
        for (TargetApp app : allItems) {
            boolean isSelected = selectedPackages.contains(app.packageName);
            if (onlySelected && !isSelected) {
                continue;
            }
            if (!showSystemApps && app.isSystemApp && !isSelected) {
                continue;
            }
            if (!searchQuery.isEmpty()) {
                boolean matchLabel = app.label != null
                        && app.label.toLowerCase(Locale.getDefault()).contains(searchQuery);
                boolean matchPkg = app.packageName != null
                        && app.packageName.toLowerCase(Locale.getDefault()).contains(searchQuery);
                if (!matchLabel && !matchPkg) {
                    continue;
                }
            }
            items.add(app);
        }
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).packageName.hashCode();
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
        holder.itemView.setBackgroundResource(
                selected ? R.drawable.bg_app_item_selected : R.drawable.bg_app_item_normal);
        holder.itemView.setOnClickListener(v -> {
            int adapterPos = holder.getBindingAdapterPosition();
            if (adapterPos == RecyclerView.NO_POSITION) {
                return;
            }
            TargetApp target = items.get(adapterPos);
            boolean newSelected = !selectedPackages.contains(target.packageName);
            if (newSelected) {
                selectedPackages.add(target.packageName);
            } else {
                selectedPackages.remove(target.packageName);
            }
            if (onlySelected && !newSelected) {
                items.remove(adapterPos);
                notifyItemRemoved(adapterPos);
            } else {
                notifyItemChanged(adapterPos);
            }
            listener.onAppSelectionChanged(target, newSelected);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final MaterialCheckBox checkBox;

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.appIcon);
            title = itemView.findViewById(R.id.appTitle);
            subtitle = itemView.findViewById(R.id.appPackage);
            checkBox = itemView.findViewById(R.id.appCheckBox);
        }
    }
}
