package com.example.big.smb;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.example.big.SmbBrowserActivity;

import java.lang.reflect.Field;
import java.util.List;

/** Injects only the network boundary; navigation still uses the production view model. */
public final class SmbFavoritesBrowserFixture {
    private SmbFavoritesBrowserFixture() {
    }

    public static void connect(SmbBrowserActivity activity, SmbClient client,
                               SmbLocation location, List<SmbEntry> entries) {
        connect(activity, client, location, entries, new SmbSession(location, "", "", "", true));
    }

    public static void connect(SmbBrowserActivity activity, SmbClient client,
                               SmbLocation location, List<SmbEntry> entries, SmbSession session) {
        SmbBrowserViewModel model = new ViewModelProvider(activity).get(SmbBrowserViewModel.class);
        model.disconnect();
        set(model, "client", client);
        set(model, "sessionToken", session.register());
        set(model, "retryLocation", location);
        ((MutableLiveData<SmbBrowserViewModel.State>) model.getState()).setValue(
                new SmbBrowserViewModel.State(location, entries, false, null));
    }

    private static void set(SmbBrowserViewModel model, String name, Object value) {
        try {
            Field field = SmbBrowserViewModel.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(model, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}