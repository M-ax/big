package com.example.big.smb;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.big.R;

import java.util.Collections;
import java.util.List;

public final class SmbBrowserAdapter extends BaseAdapter {
    private final Context context;
    private SmbBrowserRows rows = new SmbBrowserRows(Collections.emptyList());
    private boolean shares;

    public SmbBrowserAdapter(Context context) {
        this.context = context;
    }

    public void submit(List<SmbEntry> entries, boolean shares) {
        rows = new SmbBrowserRows(entries);
        this.shares = shares;
        notifyDataSetChanged();
    }

    public SmbBrowserRows getRows() {
        return rows;
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public SmbEntry getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return getItem(position) != null;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position) == null ? 1 : 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        SmbEntry entry = getItem(position);
        View row = convertView == null ? LayoutInflater.from(context).inflate(entry == null
                ? R.layout.smb_browser_separator : R.layout.smb_browser_row, parent, false) : convertView;
        if (entry == null) {
            return row;
        }
        ((TextView) row.findViewById(android.R.id.text1)).setText(entry.name);
        SmbEntry.Type type = entry.getType();
        int icon = type == SmbEntry.Type.DIRECTORY ? R.drawable.smb_ic_folder
                : type == SmbEntry.Type.VIDEO ? R.drawable.smb_ic_video
                : type == SmbEntry.Type.AUDIO ? R.drawable.smb_ic_audio : R.drawable.smb_ic_file;
        ImageView image = row.findViewById(R.id.smb_row_icon);
        image.setImageResource(icon);
        image.setTag(type);
        TextView badge = row.findViewById(R.id.smb_row_extension);
        boolean media = type == SmbEntry.Type.VIDEO || type == SmbEntry.Type.AUDIO;
        badge.setText(media ? entry.getExtension() : "");
        badge.setVisibility(media ? View.VISIBLE : View.GONE);
        TextView detail = row.findViewById(android.R.id.text2);
        if (entry.directory) {
            detail.setText(shares ? R.string.smb_share : R.string.smb_folder);
        } else {
            String label = context.getString(type == SmbEntry.Type.VIDEO ? R.string.smb_browser_video
                    : type == SmbEntry.Type.AUDIO ? R.string.smb_browser_audio : R.string.smb_file);
            detail.setText(entry.size >= 0 ? context.getString(R.string.smb_file_detail, label,
                    Formatter.formatShortFileSize(context, entry.size)) : label);
        }
        return row;
    }
}