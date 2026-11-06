package com.example.big;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.big.playback.PlaybackInput;
import com.example.big.smb.SmbFavoritesSessions;
import com.example.big.smb.SmbFavoritesStore;
import com.example.big.smb.SmbLocation;
import com.example.big.smb.SmbSession;
import com.google.android.material.button.MaterialButton;

import java.net.URI;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private SmbFavoritesStore favorites;
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    openPlayer(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        favorites = new SmbFavoritesStore(this);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.home_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        findViewById(R.id.open_file).setOnClickListener(view -> {
            try {
                filePicker.launch(new String[]{"video/*", "audio/*"});
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.open_stream).setOnClickListener(view -> showStreamDialog());
        findViewById(R.id.open_smb).setOnClickListener(view ->
                startActivity(new Intent(this, SmbDiscoveryActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderFavorites();
    }

    private void renderFavorites() {
        LinearLayout container = findViewById(R.id.smb_favorites);
        container.removeAllViews();
        List<SmbLocation> locations = favorites.getAll();
        findViewById(R.id.smb_favorites_empty).setVisibility(locations.isEmpty() ? View.VISIBLE : View.GONE);
        for (SmbLocation location : locations) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            MaterialButton open = new MaterialButton(this);
            open.setText(location.getDisplayPath());
            open.setAllCaps(false);
            open.setMaxLines(3);
            open.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            open.setIconResource(R.drawable.ic_folder);
            open.setContentDescription(getString(R.string.smb_favorite_open, location.getDisplayPath()));
            open.setOnClickListener(view -> openFavorite(location));
            row.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            AppCompatImageButton remove = new AppCompatImageButton(
                    new ContextThemeWrapper(this, R.style.Widget_Big_IconButton), null, 0);
            remove.setImageResource(R.drawable.ic_delete);
            remove.setContentDescription(getString(R.string.smb_favorite_remove, location.getDisplayPath()));
            remove.setTooltipText(remove.getContentDescription());
            remove.setOnClickListener(view -> {
                favorites.remove(location);
                renderFavorites();
                Toast.makeText(this, R.string.smb_favorite_removed, Toast.LENGTH_SHORT).show();
            });
            int size = Math.round(48 * getResources().getDisplayMetrics().density);
            row.addView(remove, new LinearLayout.LayoutParams(size, size));
            container.addView(row);
        }
    }

    private void openFavorite(SmbLocation location) {
        Intent intent = new Intent(this, SmbBrowserActivity.class)
                .putExtra(SmbBrowserActivity.EXTRA_ADDRESS, location.getUrl());
        String token = SmbFavoritesSessions.findToken(this, location);
        if (token != null) {
            intent.putExtra(SmbSession.EXTRA_SESSION, token);
        }
        startActivity(intent);
    }

    private void showStreamDialog() {
        EditText address = new EditText(this);
        address.setHint(R.string.stream_hint);
        address.setContentDescription(getString(R.string.stream_address));
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        address.setSaveEnabled(false);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, 0, padding, 0);
        container.addView(address);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.open_stream)
                .setMessage(R.string.stream_help)
                .setView(container)
                .setPositiveButton(R.string.play, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    URI uri = PlaybackInput.parseNetworkUrl(address.getText().toString());
                    if (uri == null) {
                        address.setError(getString(R.string.invalid_stream));
                        return;
                    }
                    dialog.dismiss();
                    openPlayer(Uri.parse(uri.toASCIIString()));
                }));
        dialog.show();
    }

    private void openPlayer(Uri uri) {
        Intent intent = new Intent(this, PlayerActivity.class).setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }
}