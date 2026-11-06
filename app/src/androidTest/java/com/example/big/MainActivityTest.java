package com.example.big;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.smb.SmbCredentialsStore;
import com.example.big.smb.SmbFavoritesSessions;
import com.example.big.smb.SmbFavoritesStore;
import com.example.big.smb.SmbLocation;
import com.example.big.smb.SmbSession;
import com.google.android.material.button.MaterialButton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasErrorText;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class MainActivityTest {
    @Before
    @After
    public void clearFavorites() {
        SmbFavoritesStore store = new SmbFavoritesStore(ApplicationProvider.getApplicationContext());
        for (SmbLocation location : store.getAll()) {
            store.remove(location);
        }
    }

    @Test
    public void launcherExplainsPendingIndexAndRejectsInvalidStreams() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText(R.string.indexing_title)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.open_stream)).perform(scrollTo(), click());
            onView(instanceOf(EditText.class)).perform(replaceText("not a URL"), closeSoftKeyboard());
            onView(withText(R.string.play)).perform(click());
            onView(instanceOf(EditText.class)).check(matches(hasErrorText(
                    ApplicationProvider.getApplicationContext().getString(R.string.invalid_stream))));
            onView(withText(R.string.cancel)).perform(click());
            onView(withId(R.id.open_file)).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test
    public void favoritesPersistOnHomeAndCanBeRemovedAccessibly() {
        Context context = ApplicationProvider.getApplicationContext();
        SmbLocation folder = SmbLocation.parse("home-favorites-test/Media/My%20Films/");
        SmbFavoritesStore store = new SmbFavoritesStore(context);
        store.add(folder);
        store.add(folder);
        assertTrue(new SmbCredentialsStore(context).save(folder,
                new SmbSession(folder, "alice", "private-password", "", false)));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                LinearLayout favorites = activity.findViewById(R.id.smb_favorites);
                assertEquals(1, favorites.getChildCount());
                ImageButton remove = (ImageButton) ((LinearLayout) favorites.getChildAt(0)).getChildAt(1);
                int size = Math.round(48 * activity.getResources().getDisplayMetrics().density);
                assertEquals(size, remove.getLayoutParams().width);
                assertEquals(size, remove.getLayoutParams().height);
                assertNotNull(remove.getDrawable());
                assertTrue(remove.isFocusable());
            });
            scenario.recreate();
            onView(withContentDescription(context.getString(R.string.smb_favorite_open,
                    folder.getDisplayPath()))).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription(context.getString(R.string.smb_favorite_remove,
                    folder.getDisplayPath()))).perform(scrollTo(), click());
            assertTrue(new SmbFavoritesStore(context).getAll().isEmpty());
            assertNull(new SmbCredentialsStore(context).load(folder));
            scenario.recreate();
            onView(withId(R.id.smb_favorites_empty)).perform(scrollTo()).check(matches(isDisplayed()));
            scenario.onActivity(activity -> assertEquals(0,
                    ((LinearLayout) activity.findViewById(R.id.smb_favorites)).getChildCount()));
        }
    }

    @Test
    public void scrollingLongFavoritesListFullyRevealsTargetsInBothDirections() {
        Context context = ApplicationProvider.getApplicationContext();
        SmbFavoritesStore store = new SmbFavoritesStore(context);
        for (int index = 0; index < 20; index++) {
            store.add(SmbLocation.parse("home-scroll-test/Media/Folder" + index + "/"));
        }
        List<SmbLocation> locations = store.getAll();
        SmbLocation first = locations.get(0);
        SmbLocation last = locations.get(locations.size() - 1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withContentDescription(context.getString(R.string.smb_favorite_open,
                    last.getDisplayPath()))).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
            onView(withContentDescription(context.getString(R.string.smb_favorite_remove,
                    last.getDisplayPath()))).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
            onView(withText(R.string.engine_credit)).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
            onView(withContentDescription(context.getString(R.string.smb_favorite_open,
                    first.getDisplayPath()))).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
            onView(withId(R.id.open_smb)).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
        }
    }

    @Test
    public void favoriteWithoutSessionOpensExactFolderInLoginWithoutCredentials() {
        Context context = ApplicationProvider.getApplicationContext();
        SmbLocation folder = SmbLocation.parse("home-favorites-test:1445/Media/My%20Films/100%25/");
        new SmbFavoritesStore(context).add(folder);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withContentDescription(context.getString(R.string.smb_favorite_open,
                    folder.getDisplayPath()))).perform(scrollTo(), click());
            onView(withId(R.id.smb_login)).check(matches(isDisplayed()));
            onView(withId(R.id.smb_address)).check(matches(withText(folder.getUrl())));
            onView(withId(R.id.smb_username)).check(matches(withText("")));
            onView(withId(R.id.smb_password)).check(matches(withText("")));
            onView(withId(R.id.smb_domain)).check(matches(withText("")));
            onView(withId(R.id.smb_back)).perform(click());
        }
    }

    @Test
    public void favoritePassesOnlyExactPathAndCompatibleLiveToken() {
        Context context = ApplicationProvider.getApplicationContext();
        SmbLocation folder = SmbLocation.parse("home-favorites-live:1445/Media/Exact%20Folder/");
        new SmbFavoritesStore(context).add(folder);
        String token = new SmbSession(folder, "alice", "private-password", "DOMAIN", false).register();
        assertTrue(SmbFavoritesSessions.remember(context, token, folder));
        AtomicReference<Intent> opened = new AtomicReference<>();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null && SmbBrowserActivity.class.getName()
                        .equals(intent.getComponent().getClassName())) {
                    opened.set(new Intent(intent));
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withContentDescription(context.getString(R.string.smb_favorite_open,
                    folder.getDisplayPath()))).perform(scrollTo(), click());
            assertNotNull(opened.get());
            assertEquals(folder.getUrl(), opened.get().getStringExtra(SmbBrowserActivity.EXTRA_ADDRESS));
            assertEquals(token, opened.get().getStringExtra(SmbSession.EXTRA_SESSION));
            assertEquals(2, opened.get().getExtras().size());
            SmbSession.remove(token);
            opened.set(null);
            onView(withContentDescription(context.getString(R.string.smb_favorite_open,
                    folder.getDisplayPath()))).perform(click());
            assertNotNull(opened.get());
            assertEquals(folder.getUrl(), opened.get().getStringExtra(SmbBrowserActivity.EXTRA_ADDRESS));
            String recovered = opened.get().getStringExtra(SmbSession.EXTRA_SESSION);
            assertNotNull(recovered);
            assertNotEquals(token, recovered);
            assertNotNull(SmbSession.find(recovered, folder.getUrl()));
            assertEquals(2, opened.get().getExtras().size());
            assertFalse(opened.get().toUri(0).contains("alice"));
            assertFalse(opened.get().toUri(0).contains("private-password"));
            assertFalse(opened.get().toUri(0).contains("DOMAIN"));
            SmbSession.remove(recovered);
        } finally {
            instrumentation.removeMonitor(monitor);
            SmbSession.remove(token);
        }
    }

    @Test
    public void primaryActionsHaveLeadingIconsAndReadableLabels() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                for (int id : new int[]{R.id.open_smb, R.id.open_file, R.id.open_stream}) {
                    MaterialButton button = activity.findViewById(id);
                    assertNotNull(button.getIcon());
                    assertFalse(button.getText().toString().isEmpty());
                }
            });
        }
    }
}