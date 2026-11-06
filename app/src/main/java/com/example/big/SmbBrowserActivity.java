package com.example.big;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.big.smb.SmbBrowserAdapter;
import com.example.big.smb.SmbBrowserListView;
import com.example.big.smb.SmbBrowserViewModel;
import com.example.big.smb.SmbEntry;
import com.example.big.smb.SmbException;
import com.example.big.smb.SmbFavoritesSessions;
import com.example.big.smb.SmbFavoritesStore;
import com.example.big.smb.SmbLocation;
import com.example.big.smb.SmbQuickReturn;
import com.example.big.smb.SmbScrollPosition;
import com.example.big.smb.SmbSession;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class SmbBrowserActivity extends AppCompatActivity {
    public static final String EXTRA_ADDRESS = "com.example.big.SMB_ADDRESS";
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final String SCROLL_STATE = "smb_browser_scroll";
    private SmbBrowserViewModel model;
    private SmbFavoritesStore favorites;
    private View header;
    private View controls;
    private ViewGroup controlsPlaceholder;
    private ViewGroup quickPanel;
    private SmbLocation displayedLocation;
    private EditText address;
    private EditText username;
    private EditText password;
    private EditText domain;
    private CheckBox guest;
    private final List<SmbEntry> entries = new ArrayList<>();
    private SmbBrowserAdapter adapter;
    private SmbBrowserListView browserList;
    private final SmbQuickReturn quickReturn = new SmbQuickReturn();
    private boolean showingBrowser;
    private boolean rendering;
    private final ActivityResultLauncher<String> networkPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    connect();
                } else {
                    TextView status = header.findViewById(R.id.smb_status);
                    status.setText(R.string.smb_permission_denied);
                    status.setVisibility(View.VISIBLE);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smb_browser);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.smb_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        model = new ViewModelProvider(this).get(SmbBrowserViewModel.class);
        model.restoreScrollPositions(savedInstanceState == null ? null : savedInstanceState.getBundle(SCROLL_STATE));
        model.setFavoritesContext(this);
        favorites = new SmbFavoritesStore(this);
        header = findViewById(R.id.smb_header);
        controls = findViewById(R.id.smb_controls);
        controlsPlaceholder = findViewById(R.id.smb_controls_placeholder);
        quickPanel = findViewById(R.id.smb_quick_title);
        address = findViewById(R.id.smb_address);
        String selectedAddress = getIntent().getStringExtra(EXTRA_ADDRESS);
        if (selectedAddress != null) {
            try {
                String canonicalAddress = SmbLocation.parse(selectedAddress).getUrl();
                address.setText(canonicalAddress);
                getIntent().putExtra(EXTRA_ADDRESS, canonicalAddress);
            } catch (IllegalArgumentException ignored) {
                getIntent().removeExtra(EXTRA_ADDRESS);
                // Only a validated address may prefill the login form.
            }
        }
        username = findViewById(R.id.smb_username);
        password = findViewById(R.id.smb_password);
        domain = findViewById(R.id.smb_domain);
        guest = findViewById(R.id.smb_guest);
        guest.setOnCheckedChangeListener((button, checked) -> updateLoginFields());
        adapter = new SmbBrowserAdapter(this);
        browserList = findViewById(R.id.smb_entries);
        browserList.setQuickReturnHeader(controls, controlsPlaceholder);
        ListView list = browserList;
        ((ViewGroup) header.getParent()).removeView(header);
        list.addHeaderView(header, null, false);
        list.setItemsCanFocus(true);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (!rendering && !browserList.isRestoring() && view.getChildCount() > 0) {
                    quickReturn.onScroll(first, view.getChildAt(0).getTop());
                    renderQuickPanel();
                }
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (model.getState().getValue().busy) {
                return;
            }
            int index = position - list.getHeaderViewsCount();
            if (index < 0 || index >= adapter.getCount()) {
                return;
            }
            SmbEntry entry = adapter.getItem(index);
            if (entry == null) {
                return;
            }
            if (entry.directory) {
                model.navigate(entry.location);
            } else if (SmbEntry.isPlayable(entry.name)) {
                play(entry);
            } else {
                new AlertDialog.Builder(this).setTitle(R.string.smb_open_file)
                        .setMessage(R.string.smb_unknown_file)
                        .setPositiveButton(R.string.play, (dialog, which) -> play(entry))
                        .setNegativeButton(R.string.cancel, null).show();
            }
        });
        header.findViewById(R.id.smb_connect).setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this,
                    LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                networkPermission.launch(LOCAL_NETWORK_PERMISSION);
            } else {
                connect();
            }
        });
        controls.findViewById(R.id.smb_disconnect).setOnClickListener(view -> model.disconnect());
        findViewById(R.id.smb_favorite).setOnClickListener(view -> addFavorite());
        header.findViewById(R.id.smb_cancel).setOnClickListener(view -> model.disconnect());
        controls.findViewById(R.id.smb_up).setOnClickListener(view ->
                model.navigate(model.getState().getValue().location.parent()));
        controls.findViewById(R.id.smb_refresh).setOnClickListener(view ->
                model.navigate(model.getState().getValue().location));
        header.findViewById(R.id.smb_retry).setOnClickListener(view -> model.retry());
        controls.findViewById(R.id.smb_back).setOnClickListener(view -> goBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });
        model.getState().observe(this, this::render);
        if (!model.hasConnectionAttempted()) {
            resumeFavoriteSession();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SmbBrowserViewModel.State state = model.getState().getValue();
        if (state.location != null && SmbSession.find(model.getSessionToken(), state.location.getUrl()) == null) {
            model.disconnect();
        }
        renderFavorite(model.getState().getValue());
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        captureScrollPosition();
        outState.putBundle(SCROLL_STATE, model.saveScrollPositions());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        captureScrollPosition();
        super.onPause();
    }

    private void captureScrollPosition() {
        if (displayedLocation == null || rendering || browserList.isRestoring()
                || browserList.getChildCount() == 0) {
            return;
        }
        int first = browserList.getFirstVisiblePosition();
        int index = first - browserList.getHeaderViewsCount();
        String anchor = index >= 0 && index < adapter.getCount() ? adapter.getRows().anchorAt(index) : null;
        model.saveScrollPosition(displayedLocation, new SmbScrollPosition(first,
                browserList.getChildAt(0).getTop() - browserList.getPaddingTop(), anchor,
                quickReturn.isVisible()));
    }

    private void restoreScrollPosition(SmbScrollPosition position) {
        SmbScrollPosition saved = position == null ? new SmbScrollPosition(0, 0, null, true) : position;
        browserList.restoreBeforeDraw(saved.resolve(adapter.getRows(), browserList.getHeaderViewsCount()),
                saved.top, () -> {
                    quickReturn.reset(browserList.getFirstVisiblePosition(),
                            browserList.getChildAt(0).getTop(), saved.titleVisible);
                    renderQuickPanel();
                });
    }

    private void renderQuickPanel() {
        int top = browserList.getFirstVisiblePosition() == 0 && browserList.getChildCount() > 0
                ? header.getTop() + controlsPlaceholder.getTop() : -controls.getMeasuredHeight();
        boolean inlineVisible = top + controls.getMeasuredHeight() > browserList.getPaddingTop();
        quickPanel.setTranslationY(quickReturn.isVisible() ? Math.max(browserList.getPaddingTop(), top) : top);
        quickPanel.setVisibility(showingBrowser && (inlineVisible || quickReturn.isVisible())
                ? View.VISIBLE : View.GONE);
    }

    private void resumeFavoriteSession() {
        String token = getIntent().getStringExtra(SmbSession.EXTRA_SESSION);
        getIntent().removeExtra(SmbSession.EXTRA_SESSION);
        try {
            SmbLocation location = SmbLocation.parse(address.getText().toString());
            SmbSession session = SmbFavoritesSessions.takeToken(token, location);
            if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this,
                    LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (session == null && favorites.contains(location)) {
                session = SmbFavoritesSessions.takeToken(SmbFavoritesSessions.findToken(this, location), location);
            }
            if (!location.isServerRoot() && session != null) {
                model.connect(location, session);
            }
        } catch (IllegalArgumentException ignored) {
            // An expired or incompatible session leaves only the validated login prefill.
        }
    }

    private boolean canFavorite(SmbBrowserViewModel.State state) {
        return state != null && state.location != null && state.location.isDirectory()
                && !state.location.isServerRoot() && !state.busy && state.error == null
                && SmbSession.find(model.getSessionToken(), state.location.getUrl()) != null;
    }

    private void addFavorite() {
        SmbBrowserViewModel.State state = model.getState().getValue();
        if (!canFavorite(state)) {
            return;
        }
        boolean added = favorites.add(state.location);
        boolean loginSaved = SmbFavoritesSessions.remember(this, model.getSessionToken(), state.location);
        renderFavorite(state);
        Toast.makeText(this, !loginSaved ? R.string.smb_credentials_save_failed
                : added ? R.string.smb_favorite_added : R.string.smb_favorite_saved,
                loginSaved ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }

    private void renderFavorite(SmbBrowserViewModel.State state) {
        FloatingActionButton button = findViewById(R.id.smb_favorite);
        boolean available = canFavorite(state);
        boolean saved = available && favorites.contains(state.location);
        button.setVisibility(available ? View.VISIBLE : View.GONE);
        button.setEnabled(available);
        button.setSelected(saved);
        button.setImageResource(saved ? R.drawable.ic_star : R.drawable.ic_star_outline);
        button.setContentDescription(getString(saved ? R.string.smb_favorite_saved : R.string.smb_favorite_add));
        button.setTooltipText(button.getContentDescription());
    }

    private void connect() {
        SmbLocation location;
        try {
            location = SmbLocation.parse(address.getText().toString());
        } catch (IllegalArgumentException exception) {
            address.setError(getString(R.string.smb_invalid_address));
            return;
        }
        if (!guest.isChecked() && username.getText().toString().trim().isEmpty()) {
            username.setError(getString(R.string.smb_username_required));
            return;
        }
        SmbSession session;
        try {
            session = new SmbSession(location, username.getText().toString(),
                    password.getText().toString(), domain.getText().toString(), guest.isChecked());
        } catch (IllegalArgumentException exception) {
            password.setError(getString(R.string.smb_invalid_credentials));
            return;
        }
        password.setText("");
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(address.getWindowToken(), 0);
        model.connect(location, session);
    }

    private void updateLoginFields() {
        boolean busy = model.getState().getValue().busy;
        address.setEnabled(!busy);
        guest.setEnabled(!busy);
        username.setEnabled(!busy && !guest.isChecked());
        password.setEnabled(!busy && !guest.isChecked());
        domain.setEnabled(!busy && !guest.isChecked());
        header.findViewById(R.id.smb_connect).setEnabled(!busy);
    }

    private void render(SmbBrowserViewModel.State state) {
        captureScrollPosition();
        rendering = true;
        boolean connected = state.location != null
                && SmbSession.find(model.getSessionToken(), state.location.getUrl()) != null;
        showingBrowser = connected;
        ViewGroup controlsParent = connected ? quickPanel : controlsPlaceholder;
        if (controls.getParent() != controlsParent) {
            ((ViewGroup) controls.getParent()).removeView(controls);
            controlsParent.addView(controls);
        }
        String title = connected ? getString(R.string.smb_connected_title, state.location.getHost())
                : getString(R.string.smb_title);
        ((TextView) controls.findViewById(R.id.smb_title)).setText(title);
        setTitle(title);
        header.findViewById(R.id.smb_login).setVisibility(connected ? View.GONE : View.VISIBLE);
        header.findViewById(R.id.smb_browser).setVisibility(connected ? View.VISIBLE : View.GONE);
        header.findViewById(R.id.smb_progress).setVisibility(state.busy ? View.VISIBLE : View.GONE);
        header.findViewById(R.id.smb_cancel).setVisibility(state.busy ? View.VISIBLE : View.GONE);
        for (int id : new int[]{R.id.smb_up, R.id.smb_refresh, R.id.smb_disconnect}) {
            controls.findViewById(id).setVisibility(connected ? View.VISIBLE : View.GONE);
        }
        TextView status = header.findViewById(R.id.smb_status);
        status.setVisibility(state.busy || state.error != null ? View.VISIBLE : View.GONE);
        if (state.busy) {
            status.setText(R.string.smb_loading);
        } else if (state.error != null) {
            status.setText(state.error == SmbException.Kind.AUTHENTICATION
                    && favorites.contains(model.getLoginLocation())
                    ? R.string.smb_credentials_auth_failed : errorText(state.error));
        }
        if (!connected && model.getLoginLocation() != null) {
            address.setText(model.getLoginLocation().getUrl());
        }
        if (!connected && state.error == SmbException.Kind.AUTHENTICATION) {
            password.setText("");
        }
        header.findViewById(R.id.smb_retry).setVisibility(connected && state.error != null
                && !state.busy ? View.VISIBLE : View.GONE);
        controls.findViewById(R.id.smb_up).setEnabled(connected && !state.busy && !state.location.isServerRoot());
        controls.findViewById(R.id.smb_refresh).setEnabled(!state.busy);
        controls.findViewById(R.id.smb_path).setVisibility(connected ? View.VISIBLE : View.GONE);
        if (connected) {
            ((TextView) controls.findViewById(R.id.smb_path)).setText(state.location.getDisplayPath());
        }
        if (!entries.equals(state.entries)) {
            entries.clear();
            entries.addAll(state.entries);
            adapter.submit(entries, connected && state.location.isServerRoot());
        }
        TextView empty = header.findViewById(R.id.smb_empty);
        empty.setVisibility(connected && !state.busy && state.entries.isEmpty()
                && state.error == null ? View.VISIBLE : View.GONE);
        if (connected) {
            empty.setText(state.location.isServerRoot() ? R.string.smb_no_shares : R.string.smb_empty_folder);
        }
        if (connected && !model.rememberSuccessfulListing(state)) {
            Toast.makeText(this, R.string.smb_credentials_save_failed, Toast.LENGTH_LONG).show();
        }
        if (connected) {
            restoreScrollPosition(model.getScrollPosition(state.location));
        } else if (displayedLocation != null) {
            restoreScrollPosition(null);
        }
        displayedLocation = connected ? state.location : null;
        rendering = false;
        renderQuickPanel();
        renderFavorite(state);
        updateLoginFields();
    }

    private int errorText(SmbException.Kind kind) {
        switch (kind) {
            case AUTHENTICATION: return R.string.smb_auth_error;
            case ACCESS_DENIED: return R.string.smb_access_error;
            case NOT_FOUND: return R.string.smb_missing_error;
            case NETWORK: return R.string.smb_network_error;
            default: return R.string.smb_other_error;
        }
    }

    private void play(SmbEntry entry) {
        startActivity(new Intent(this, PlayerActivity.class)
                .setData(Uri.parse(entry.location.getUrl()))
                .putExtra(SmbSession.EXTRA_SESSION, model.getSessionToken()));
    }

    private void goBack() {
        SmbBrowserViewModel.State state = model.getState().getValue();
        if (state.busy) {
            model.disconnect();
        } else if (state.location != null && !state.location.isServerRoot()) {
            model.navigate(state.location.parent());
        } else {
            finish();
        }
    }
}