package com.example.big;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.big.smb.SmbDiscoveryViewModel;

import java.util.ArrayList;
import java.util.List;

public class SmbDiscoveryActivity extends AppCompatActivity {
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private SmbDiscoveryViewModel model;
    private final List<SmbDiscoveryViewModel.Server> servers = new ArrayList<>();
    private ArrayAdapter<SmbDiscoveryViewModel.Server> adapter;
    private final ActivityResultLauncher<String> networkPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    model.start();
                } else {
                    model.permissionRequired();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smb_discovery);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.smb_discovery_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        model = new ViewModelProvider(this).get(SmbDiscoveryViewModel.class);
        adapter = new ArrayAdapter<SmbDiscoveryViewModel.Server>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, servers) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                SmbDiscoveryViewModel.Server server = getItem(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText(server.name);
                ((TextView) row.findViewById(android.R.id.text2)).setText(server.location.getDisplayPath());
                return row;
            }
        };
        ListView list = findViewById(R.id.smb_discovery_servers);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            SmbDiscoveryViewModel.Server server = servers.get(position);
            model.stop();
            startActivity(new Intent(this, SmbBrowserActivity.class)
                    .putExtra(SmbBrowserActivity.EXTRA_ADDRESS, server.location.getUrl()));
        });
        findViewById(R.id.smb_add_share).setOnClickListener(view -> {
            model.stop();
            startActivity(new Intent(this, SmbBrowserActivity.class));
        });
        findViewById(R.id.smb_discovery_back).setOnClickListener(view -> finish());
        findViewById(R.id.smb_discovery_refresh).setOnClickListener(view -> discover());
        findViewById(R.id.smb_discovery_stop).setOnClickListener(view -> model.stop());
        model.getState().observe(this, this::render);
        if (model.getState().getValue().status == SmbDiscoveryViewModel.Status.IDLE) {
            discover();
        }
    }

    private void discover() {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this,
                LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            model.permissionRequired();
            networkPermission.launch(LOCAL_NETWORK_PERMISSION);
        } else {
            model.start();
        }
    }

    private void render(SmbDiscoveryViewModel.State state) {
        boolean busy = state.status == SmbDiscoveryViewModel.Status.SEARCHING;
        findViewById(R.id.smb_discovery_progress).setVisibility(busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.smb_discovery_stop).setVisibility(busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.smb_discovery_refresh).setEnabled(!busy);
        int text;
        switch (state.status) {
            case SEARCHING: text = R.string.smb_discovery_searching; break;
            case STOPPED: text = R.string.smb_discovery_stopped; break;
            case NO_NETWORK: text = R.string.smb_discovery_no_network; break;
            case ERROR: text = R.string.smb_discovery_error; break;
            case PERMISSION_REQUIRED: text = R.string.smb_discovery_permission; break;
            default: text = state.servers.isEmpty() ? R.string.smb_discovery_empty
                    : R.string.smb_discovery_done; break;
        }
        ((TextView) findViewById(R.id.smb_discovery_status)).setText(text);
        if (!servers.equals(state.servers)) {
            servers.clear();
            servers.addAll(state.servers);
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onStop() {
        if (!isChangingConfigurations()) {
            model.stop();
        }
        super.onStop();
    }
}