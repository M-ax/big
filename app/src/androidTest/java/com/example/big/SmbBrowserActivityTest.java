package com.example.big;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;

import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.smb.SmbBrowserViewModel;
import com.example.big.smb.SmbClient;
import com.example.big.smb.SmbCredentialsStore;
import com.example.big.smb.SmbEntry;
import com.example.big.smb.SmbException;
import com.example.big.smb.SmbFavoritesBrowserFixture;
import com.example.big.smb.SmbFavoritesSessions;
import com.example.big.smb.SmbFavoritesStore;
import com.example.big.smb.SmbLocation;
import com.example.big.smb.SmbSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasErrorText;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class SmbBrowserActivityTest {
    @Before
    @After
    public void clearFavorites() {
        SmbFavoritesStore store = new SmbFavoritesStore(ApplicationProvider.getApplicationContext());
        for (SmbLocation location : store.getAll()) {
            store.remove(location);
        }
    }

    @Before
    public void allowLocalNetwork() {
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                    "com.example.big", "android.permission.ACCESS_LOCAL_NETWORK");
        }
    }

    @Test
    public void homeOpensLoginAndValidatesAddressAndUsername() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.open_smb)).perform(click());
            onView(withText(R.string.smb_discovery_title)).check(matches(isDisplayed()));
            onView(withId(R.id.smb_add_share)).perform(click());
            onView(withId(R.id.smb_title)).check(matches(withText("Add SMB Share")))
                    .check(matches(isDisplayed()));
            onView(withId(R.id.smb_address)).check(matches(isDisplayed()));
            onView(withId(R.id.smb_address)).perform(replaceText("smb://user:secret@server/share"), closeSoftKeyboard());
            onView(withId(R.id.smb_connect)).perform(scrollTo(), click());
            onView(withId(R.id.smb_address)).check(matches(hasErrorText(context.getString(R.string.smb_invalid_address))));
            onView(withId(R.id.smb_address)).perform(replaceText("smb://server/share"), closeSoftKeyboard());
            onView(withId(R.id.smb_connect)).perform(scrollTo(), click());
            onView(withId(R.id.smb_username)).check(matches(hasErrorText(context.getString(R.string.smb_username_required))));
            onView(withId(R.id.smb_guest)).perform(scrollTo(), click());
            onView(withId(R.id.smb_username)).check(matches(not(isEnabled())));
            onView(withId(R.id.smb_password)).check(matches(not(isEnabled())));
            onView(withId(R.id.smb_back)).perform(scrollTo(), click());
            onView(withId(R.id.smb_add_share)).check(matches(isDisplayed()));
            onView(withId(R.id.smb_discovery_back)).perform(click());
            onView(withId(R.id.open_smb)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void selectedServerPrefillsOnlyAValidatedAddress() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), SmbBrowserActivity.class)
                .putExtra(SmbBrowserActivity.EXTRA_ADDRESS, "smb://192.168.1.12:1445/");
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withId(R.id.smb_address)).check(matches(withText("smb://192.168.1.12:1445/")));
            onView(withId(R.id.smb_password)).check(matches(withText("")));
            scenario.recreate();
            onView(withId(R.id.smb_address)).check(matches(withText("smb://192.168.1.12:1445/")));
        }
        intent.putExtra(SmbBrowserActivity.EXTRA_ADDRESS, "smb://alice:secret@server/");
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withId(R.id.smb_address)).check(matches(withText("")));
        }
    }

    @Test
    public void expiredPlayerSessionAsksForLoginAgain() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), PlayerActivity.class)
                .setData(Uri.parse("smb://server/Media/clip.mp4"))
                .putExtra(SmbSession.EXTRA_SESSION, "expired-process-session");
        try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withText(R.string.smb_session_expired)).check(matches(isDisplayed()));
            onView(withText(R.string.close)).perform(click());
        }
    }

    @Test
    public void credentialsAreNotRestoredFromActivitySavedState() {
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            onView(withId(R.id.smb_username)).perform(replaceText("alice"));
            onView(withId(R.id.smb_password)).perform(replaceText("private-password"), closeSoftKeyboard());
            onView(withId(R.id.smb_address)).perform(replaceText("smb://alice:secret@server"), closeSoftKeyboard());
            scenario.onActivity(activity -> {
                View field = activity.findViewById(R.id.smb_password);
                assertFalse(field.isSaveEnabled());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, field.getImportantForAutofill());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("", ((EditText) activity.findViewById(R.id.smb_password)).getText().toString());
                assertEquals("", ((EditText) activity.findViewById(R.id.smb_username)).getText().toString());
                assertEquals("", ((EditText) activity.findViewById(R.id.smb_address)).getText().toString());
            });
        }
    }

    @Test
    public void favoritesRequireAShareAndReflectPersistentStateAcrossRotation() throws Exception {
        SmbLocation root = SmbLocation.parse("browser-favorites-test");
        SmbLocation share = root.child("Media", true);
        DirectoryClient client = new DirectoryClient();
        SmbFavoritesStore store = new SmbFavoritesStore(ApplicationProvider.getApplicationContext());
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
            scenario.onActivity(activity -> SmbFavoritesBrowserFixture.connect(activity, client,
                    root, Collections.emptyList()));
            onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
            onView(withId(R.id.smb_up)).check(matches(not(isEnabled())));
            scenario.onActivity(activity -> model(activity).navigate(share));
            awaitState(scenario, state -> share.equals(state.location) && !state.busy);
            onView(withId(R.id.smb_favorite)).check(matches(withContentDescription(R.string.smb_favorite_add)))
                    .perform(click(), click());
            assertEquals(Collections.singletonList(share), store.getAll());
            onView(withId(R.id.smb_favorite)).check(matches(isSelected()))
                    .check(matches(withContentDescription(R.string.smb_favorite_saved)));
            scenario.recreate();
            onView(withId(R.id.smb_favorite)).check(matches(isSelected()));
            onView(withId(R.id.smb_refresh)).perform(click());
            awaitState(scenario, state -> share.equals(state.location) && !state.busy);
            onView(withId(R.id.smb_favorite)).check(matches(isSelected()));
            onView(withId(R.id.smb_up)).perform(click());
            awaitState(scenario, state -> root.equals(state.location) && !state.busy);
            onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
            onView(withId(R.id.smb_disconnect)).perform(click());
            onView(withId(R.id.smb_login)).check(matches(isDisplayed()));
            onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
        }
        assertEquals(Collections.singletonList(share), new SmbFavoritesStore(
                ApplicationProvider.getApplicationContext()).getAll());
    }

    @Test
    public void loadingAndFailedNavigationCannotFavoriteTheStaleFolder() throws Exception {
        SmbLocation previous = SmbLocation.parse("browser-favorites-test/Media/Previous");
        SmbLocation target = SmbLocation.parse("browser-favorites-test/Media/Exact%20Target");
        DirectoryClient client = new DirectoryClient();
        client.release = new CountDownLatch(1);
        client.failure = SmbException.Kind.NOT_FOUND;
        SmbFavoritesStore store = new SmbFavoritesStore(ApplicationProvider.getApplicationContext());
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> {
                SmbFavoritesBrowserFixture.connect(activity, client, previous, Collections.emptyList());
                model(activity).navigate(target);
            });
            onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
            scenario.onActivity(activity -> activity.findViewById(R.id.smb_favorite).performClick());
            assertTrue(store.getAll().isEmpty());
            scenario.recreate();
            onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
            client.release.countDown();
            awaitState(scenario, state -> !state.busy && state.error == SmbException.Kind.NOT_FOUND);
            onView(withId(R.id.smb_path)).check(matches(withText(previous.getDisplayPath())));
            onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
            scenario.onActivity(activity -> activity.findViewById(R.id.smb_favorite).performClick());
            assertTrue(store.getAll().isEmpty());
            client.failure = null;
            onView(withId(R.id.smb_retry)).perform(click());
            awaitState(scenario, state -> target.equals(state.location) && !state.busy && state.error == null);
            onView(withId(R.id.smb_favorite)).perform(click());
            assertEquals(Collections.singletonList(target), store.getAll());
        } finally {
            client.release.countDown();
        }
    }

    @Test
    public void expiredAndIncompatibleFavoriteSessionsFallBackToExactPrefilledLogin() {
        SmbLocation folder = SmbLocation.parse("browser-favorites-login:1445/Media/Exact%20Folder");
        String otherHost = new SmbSession(SmbLocation.parse("other-server:1445/Media"),
                "alice", "private-password", "DOMAIN", false).register();
        String otherPort = new SmbSession(SmbLocation.parse("browser-favorites-login/Media"),
                "alice", "private-password", "DOMAIN", false).register();
        try {
            for (String token : new String[]{"expired-session", otherHost, otherPort}) {
                Intent intent = new Intent(ApplicationProvider.getApplicationContext(), SmbBrowserActivity.class)
                        .putExtra(SmbBrowserActivity.EXTRA_ADDRESS, folder.getUrl())
                        .putExtra(SmbSession.EXTRA_SESSION, token);
                try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(intent)) {
                    onView(withId(R.id.smb_login)).check(matches(isDisplayed()));
                    onView(withId(R.id.smb_address)).check(matches(withText(folder.getUrl())));
                    onView(withId(R.id.smb_username)).check(matches(withText("")));
                    onView(withId(R.id.smb_password)).check(matches(withText("")));
                    onView(withId(R.id.smb_domain)).check(matches(withText("")));
                    onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
                    scenario.onActivity(activity -> {
                        assertNull(model(activity).getState().getValue().location);
                        assertFalse(model(activity).getState().getValue().busy);
                    });
                    scenario.recreate();
                    onView(withId(R.id.smb_address)).check(matches(withText(folder.getUrl())));
                    onView(withId(R.id.smb_password)).check(matches(withText("")));
                }
            }
        } finally {
            SmbSession.remove(otherHost);
            SmbSession.remove(otherPort);
        }
    }

    @Test
    public void navigationIconsKeepAccessibleLabelsAndTouchTargets() {
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> {
                int[] ids = {R.id.smb_back, R.id.smb_up, R.id.smb_refresh, R.id.smb_disconnect};
                int[] labels = {R.string.back, R.string.smb_up, R.string.smb_refresh, R.string.smb_disconnect};
                int minimum = Math.round(48 * activity.getResources().getDisplayMetrics().density);
                for (int index = 0; index < ids.length; index++) {
                    ImageButton button = activity.findViewById(ids[index]);
                    assertNotNull(button.getDrawable());
                    assertEquals(activity.getString(labels[index]), button.getContentDescription().toString());
                    assertTrue(button.getLayoutParams().width >= minimum);
                    assertTrue(button.getLayoutParams().height >= minimum);
                }
            });
        }
    }

    @Test
    public void successfulReloginUpdatesExistingFavoriteWithoutShowingDecryptedFields() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SmbLocation folder = SmbLocation.parse("browser-login-update/Media/Exact%20Folder");
        new SmbFavoritesStore(context).add(folder);
        SmbSession first = new SmbSession(folder, "alice", "first-password", "DOMAIN", false);
        SmbSession latest = new SmbSession(folder, "bob", "latest-password", "OTHER", false);
        assertTrue(new SmbCredentialsStore(context).save(folder, first));
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> SmbFavoritesBrowserFixture.connect(activity,
                    new DirectoryClient(), folder, Collections.emptyList(), latest));
            assertEquals(latest.mediaOptions(), new SmbCredentialsStore(context).load(folder).mediaOptions());
            scenario.recreate();
            scenario.onActivity(activity -> {
                View header = activity.findViewById(R.id.smb_header);
                assertEquals("", ((EditText) header.findViewById(R.id.smb_username)).getText().toString());
                assertEquals("", ((EditText) header.findViewById(R.id.smb_password)).getText().toString());
                assertEquals("", ((EditText) header.findViewById(R.id.smb_domain)).getText().toString());
            });
        }
        String restored = SmbFavoritesSessions.findToken(context, folder);
        try {
            assertEquals(latest.mediaOptions(), SmbSession.find(restored, folder.getUrl()).mediaOptions());
        } finally {
            SmbSession.remove(restored);
        }
    }

    @Test
    public void failedCachedLoginReturnsToExactManualLoginWithoutRetryingOnRotation() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SmbLocation folder = SmbLocation.parse("browser-stale-login:1445/Media/Exact%20Folder");
        new SmbFavoritesStore(context).add(folder);
        SmbCredentialsStore credentials = new SmbCredentialsStore(context);
        assertTrue(credentials.save(folder, new SmbSession(folder, "alice", "stale-password", "DOMAIN", false)));
        SmbSession cached = credentials.load(folder);
        DirectoryClient client = new DirectoryClient();
        client.failure = SmbException.Kind.AUTHENTICATION;
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> {
                SmbFavoritesBrowserFixture.connect(activity, client, folder, Collections.emptyList(), cached);
                model(activity).navigate(folder);
            });
            awaitState(scenario, state -> !state.busy && state.error == SmbException.Kind.AUTHENTICATION);
            assertNull(credentials.load(folder));
            assertNull(SmbFavoritesSessions.findToken(context, folder));
            assertTrue(new SmbFavoritesStore(context).contains(folder));
            onView(withId(R.id.smb_title)).check(matches(withText(R.string.smb_title)));
            onView(withId(R.id.smb_status)).check(matches(withText(R.string.smb_credentials_auth_failed)));
            onView(withId(R.id.smb_address)).check(matches(withText(folder.getUrl())));
            onView(withId(R.id.smb_password)).check(matches(withText("")));
            onView(withId(R.id.smb_username)).check(matches(withText("")));
            scenario.recreate();
            onView(withId(R.id.smb_address)).check(matches(withText(folder.getUrl())));
            scenario.onActivity(activity -> {
                assertNull(model(activity).getSessionToken());
                assertNull(model(activity).getState().getValue().location);
                assertFalse(model(activity).getState().getValue().busy);
            });
            assertEquals(1, client.calls);
        }
    }

    @Test
    public void malformedCachedLoginLeavesExactPathAndEmptyManualFields() {
        Context context = ApplicationProvider.getApplicationContext();
        SmbLocation folder = SmbLocation.parse("browser-corrupt-login/Media/Exact%20Folder");
        new SmbFavoritesStore(context).add(folder);
        context.getSharedPreferences("smb_credentials", Context.MODE_PRIVATE).edit()
                .putString(folder.getUrl(), "tampered-record").commit();
        Intent intent = new Intent(context, SmbBrowserActivity.class)
                .putExtra(SmbBrowserActivity.EXTRA_ADDRESS, folder.getUrl());
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withId(R.id.smb_title)).check(matches(withText(R.string.smb_title)));
            onView(withId(R.id.smb_address)).check(matches(withText(folder.getUrl())));
            onView(withId(R.id.smb_username)).check(matches(withText("")));
            onView(withId(R.id.smb_password)).check(matches(withText("")));
            scenario.onActivity(activity -> assertFalse(model(activity).getState().getValue().busy));
            assertFalse(context.getSharedPreferences("smb_credentials", Context.MODE_PRIVATE).contains(folder.getUrl()));
        }
    }

    @Test
    public void connectedTitleTracksRealSessionAndDisconnectReturnsToAddShare() throws Exception {
        SmbLocation root = SmbLocation.parse("192.168.1.12:1445");
        SmbLocation folder = root.child("Media", true);
        DirectoryClient client = new DirectoryClient();
        client.release = new CountDownLatch(1);
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            onView(withId(R.id.smb_title)).check(matches(withText("Add SMB Share")));
            scenario.onActivity(activity -> {
                SmbFavoritesBrowserFixture.connect(activity, client, root, Collections.emptyList());
                model(activity).navigate(folder);
            });
            onView(withId(R.id.smb_title)).check(matches(withText("SMB 192.168.1.12")));
            client.release.countDown();
            awaitState(scenario, state -> folder.equals(state.location) && !state.busy);
            scenario.recreate();
            onView(withId(R.id.smb_title)).check(matches(withText("SMB 192.168.1.12")));
            scenario.onActivity(activity -> assertEquals("SMB 192.168.1.12", activity.getTitle().toString()));
            onView(withId(R.id.smb_disconnect)).perform(click());
            onView(withId(R.id.smb_title)).check(matches(withText("Add SMB Share")));
        } finally {
            client.release.countDown();
        }
    }

    @Test
    public void headerScrollsAwayWithListingInBothOrientationsWhileFavoriteFloats() {
        SmbLocation folder = SmbLocation.parse("browser-scroll-test/Media");
        List<SmbEntry> listing = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            listing.add(new SmbEntry("Folder " + index, true, folder.child("Folder " + index, true), 0));
        }
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> SmbFavoritesBrowserFixture.connect(activity,
                    new DirectoryClient(), folder, listing));
            for (int orientation : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                rotateAndAwaitLayout(scenario, orientation);
                AtomicReference<View> header = new AtomicReference<>();
                scenario.onActivity(activity -> {
                    ListView list = activity.findViewById(R.id.smb_entries);
                    list.setSelection(0);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    ListView list = activity.findViewById(R.id.smb_entries);
                    header.set(activity.findViewById(R.id.smb_header));
                    assertEquals(1, list.getHeaderViewsCount());
                    for (int id : new int[]{R.id.smb_back, R.id.smb_title, R.id.smb_path,
                            R.id.smb_up, R.id.smb_refresh, R.id.smb_disconnect}) {
                        assertDescendant(activity.findViewById(id), activity.findViewById(R.id.smb_root));
                    }
                    assertDescendant(header.get().findViewById(R.id.smb_login), list);
                    list.setSelection(60);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    ListView list = activity.findViewById(R.id.smb_entries);
                    assertTrue(list.getFirstVisiblePosition() > 0);
                    assertFalse(header.get().isShown() && header.get().getGlobalVisibleRect(new Rect()));
                    View star = activity.findViewById(R.id.smb_favorite);
                    View root = activity.findViewById(R.id.smb_root);
                    assertSame(root, star.getParent());
                    assertTrue(star.getGlobalVisibleRect(new Rect()));
                    assertTrue(star.getBottom() <= root.getHeight() - root.getPaddingBottom());
                    assertTrue(star.getRight() <= root.getWidth() - root.getPaddingRight());
                    assertEquals(View.GONE, activity.findViewById(R.id.smb_quick_title).getVisibility());
                    list.scrollListBy(-1);
                    assertTrue("Quick return must work without reaching the header",
                            list.getFirstVisiblePosition() > 20);
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.smb_quick_title).getVisibility());
                    list.scrollListBy(1);
                    assertEquals(View.GONE, activity.findViewById(R.id.smb_quick_title).getVisibility());
                    list.setSelection(0);
                });
                onView(withId(R.id.smb_title)).check(matches(isDisplayed()));
            }
        }
    }

    @Test
    public void allQuickReturnControlsWorkAtDeepScrollInBothOrientations() throws Exception {
        SmbLocation root = SmbLocation.parse("browser-quick-controls-test");
        SmbLocation share = root.child("Media", true);
        SmbLocation folder = share.child("Folder A", true);
        for (int orientation : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
            DirectoryClient client = new DirectoryClient();
            for (SmbLocation location : new SmbLocation[]{root, share, folder}) {
                client.listings.put(location, folderListing(location));
            }
            try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
                rotateAndAwaitLayout(scenario, orientation);
                scenario.onActivity(activity -> SmbFavoritesBrowserFixture.connect(activity,
                        client, folder, client.listings.get(folder)));
                revealDeepControls(scenario, folder);
                onView(withId(R.id.smb_favorite)).perform(click()).check(matches(isSelected()));

                client.release = new CountDownLatch(1);
                onView(withId(R.id.smb_refresh)).perform(click());
                awaitState(scenario, state -> state.busy);
                assertQuickControls(folder);
                onView(withId(R.id.smb_up)).check(matches(not(isEnabled())));
                onView(withId(R.id.smb_refresh)).check(matches(not(isEnabled())));
                onView(withId(R.id.smb_disconnect)).check(matches(isEnabled()));
                onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
                client.release.countDown();
                awaitState(scenario, state -> !state.busy);
                assertEquals(1, client.calls);
                assertQuickControls(folder);
                scenario.onActivity(activity -> {
                    ListView list = activity.findViewById(R.id.smb_entries);
                    assertEquals(41, list.getFirstVisiblePosition());
                    assertEquals(-16, list.getChildAt(0).getTop() - list.getPaddingTop());
                });
                onView(withId(R.id.smb_refresh)).check(matches(isEnabled()));
                onView(withId(R.id.smb_up)).perform(click());
                awaitState(scenario, state -> share.equals(state.location) && !state.busy);
                revealDeepControls(scenario, share);
                onView(withId(R.id.smb_back)).perform(click());
                awaitState(scenario, state -> root.equals(state.location) && !state.busy);
                revealDeepControls(scenario, root);
                onView(withId(R.id.smb_up)).check(matches(not(isEnabled())));
                onView(withId(R.id.smb_favorite)).check(matches(not(isDisplayed())));
                onView(withId(R.id.smb_disconnect)).perform(click());
                awaitState(scenario, state -> state.location == null && !state.busy);
                onView(withId(R.id.smb_title)).check(matches(withText(R.string.smb_title)));
                onView(withId(R.id.smb_back)).check(matches(isCompletelyDisplayed()));
                onView(withId(R.id.smb_address)).check(matches(isDisplayed()));
                onView(withId(R.id.smb_quick_title)).check(matches(not(isDisplayed())));
                scenario.onActivity(activity -> {
                    assertNull(model(activity).getSessionToken());
                    View header = activity.findViewById(R.id.smb_header);
                    assertDescendant(activity.findViewById(R.id.smb_back), header);
                    assertDescendant(activity.findViewById(R.id.smb_address), header);
                });
            } finally {
                client.release.countDown();
            }
        }
    }

    @Test
    public void deepQuickReturnAuthenticationFailureRestoresScrollableLoginAndBack() throws Exception {
        SmbLocation folder = SmbLocation.parse("browser-quick-auth-test/Media/Exact%20Folder");
        DirectoryClient client = new DirectoryClient();
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            rotateAndAwaitLayout(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            scenario.onActivity(activity -> SmbFavoritesBrowserFixture.connect(activity,
                    client, folder, folderListing(folder)));
            revealDeepControls(scenario, folder);
            client.failure = SmbException.Kind.AUTHENTICATION;
            onView(withId(R.id.smb_refresh)).perform(click());
            awaitState(scenario, state -> state.error == SmbException.Kind.AUTHENTICATION && !state.busy);
            onView(withId(R.id.smb_address)).check(matches(withText(folder.getUrl())));
            onView(withId(R.id.smb_password)).check(matches(withText("")));
            onView(withId(R.id.smb_quick_title)).check(matches(not(isDisplayed())));
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.smb_entries);
                assertEquals(0, list.getFirstVisiblePosition());
                assertEquals(list.getPaddingTop(), list.getChildAt(0).getTop());
                for (int id : new int[]{R.id.smb_back, R.id.smb_title, R.id.smb_address,
                        R.id.smb_username, R.id.smb_password, R.id.smb_domain}) {
                    assertDescendant(activity.findViewById(id), list);
                }
            });
            onView(withId(R.id.smb_entries)).perform(swipeUp());
            onView(withId(R.id.smb_title)).check(matches(not(isDisplayed())));
            onView(withId(R.id.smb_back)).perform(scrollTo(), click());
            // The click can finish before the asynchronous activity teardown completes.
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (scenario.getState() != androidx.lifecycle.Lifecycle.State.DESTROYED
                    && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50);
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.getState());
        }
    }

    @Test
    public void loginBackAndTitleScrollWithTheFormInLandscape() {
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            rotateAndAwaitLayout(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.smb_entries);
                View header = activity.findViewById(R.id.smb_header);
                assertEquals(1, list.getCount());
                assertTrue("The landscape login form must overflow the list viewport",
                        header.getHeight() > list.getHeight() - list.getPaddingTop() - list.getPaddingBottom());
                assertTrue("The header-only login list must allow downward scrolling", list.canScrollVertically(1));
            });
            onView(withId(R.id.smb_back)).check(matches(isDisplayed()));
            onView(withId(R.id.smb_title)).check(matches(isDisplayed()));
            onView(withId(R.id.smb_entries)).perform(swipeUp());
            onView(withId(R.id.smb_back)).check(matches(not(isDisplayed())));
            onView(withId(R.id.smb_title)).check(matches(not(isDisplayed())));
            onView(withId(R.id.smb_connect)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.smb_back)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.smb_title)).check(matches(isDisplayed()));
        }
    }

    private static List<SmbEntry> folderListing(SmbLocation folder) {
        List<SmbEntry> listing = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            String name = "Folder " + index;
            listing.add(new SmbEntry(name, true, folder.child(name, true), 0));
        }
        return listing;
    }

    private static void revealDeepControls(ActivityScenario<SmbBrowserActivity> scenario, SmbLocation location) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> ((ListView) activity.findViewById(R.id.smb_entries))
                .setSelectionFromTop(41, -17));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            ListView list = activity.findViewById(R.id.smb_entries);
            assertEquals(41, list.getFirstVisiblePosition());
            assertEquals(View.GONE, activity.findViewById(R.id.smb_quick_title).getVisibility());
            list.scrollListBy(-1);
            assertEquals(41, list.getFirstVisiblePosition());
            assertEquals(-16, list.getChildAt(0).getTop() - list.getPaddingTop());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.smb_quick_title).getVisibility());
        });
        assertQuickControls(location);
        scenario.onActivity(activity -> {
            int minimum = Math.round(48 * activity.getResources().getDisplayMetrics().density);
            int[] ids = {R.id.smb_back, R.id.smb_up, R.id.smb_refresh, R.id.smb_disconnect};
            int[] labels = {R.string.back, R.string.smb_up, R.string.smb_refresh, R.string.smb_disconnect};
            for (int index = 0; index < ids.length; index++) {
                View button = activity.findViewById(ids[index]);
                assertDescendant(button, activity.findViewById(R.id.smb_quick_title));
                assertTrue(button.getWidth() >= minimum);
                assertTrue(button.getHeight() >= minimum);
                assertEquals(activity.getString(labels[index]), button.getContentDescription().toString());
            }
            ((ListView) activity.findViewById(R.id.smb_entries)).scrollListBy(1);
        });
        for (int id : new int[]{R.id.smb_title, R.id.smb_back, R.id.smb_path,
                R.id.smb_up, R.id.smb_refresh, R.id.smb_disconnect}) {
            onView(withId(id)).check(matches(not(isDisplayed())));
        }
        scenario.onActivity(activity -> ((ListView) activity.findViewById(R.id.smb_entries)).scrollListBy(-1));
        assertQuickControls(location);
    }

    private static void assertQuickControls(SmbLocation location) {
        for (int id : new int[]{R.id.smb_title, R.id.smb_back, R.id.smb_path,
                R.id.smb_up, R.id.smb_refresh, R.id.smb_disconnect}) {
            onView(withId(id)).check(matches(isCompletelyDisplayed()));
        }
        onView(withId(R.id.smb_title)).check(matches(withText("SMB " + location.getHost())));
        onView(withId(R.id.smb_path)).check(matches(withText(location.getDisplayPath())));
    }

    private static void rotateAndAwaitLayout(ActivityScenario<SmbBrowserActivity> scenario, int orientation) {
        scenario.onActivity(activity -> activity.setRequestedOrientation(orientation));
        int expected = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        long deadline = SystemClock.uptimeMillis() + 5000;
        AtomicBoolean ready = new AtomicBoolean();
        AtomicReference<String> dimensions = new AtomicReference<>();
        // Main-thread idleness alone does not wait for WindowManager to recreate and resize the activity.
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.smb_root);
                View list = activity.findViewById(R.id.smb_entries);
                View header = activity.findViewById(R.id.smb_header);
                int actual = activity.getResources().getConfiguration().orientation;
                boolean landscape = root.getWidth() > root.getHeight();
                dimensions.set("orientation=" + actual + ", root=" + root.getWidth() + "x" + root.getHeight()
                        + ", list=" + list.getWidth() + "x" + list.getHeight());
                ready.set(actual == expected && landscape == (expected == Configuration.ORIENTATION_LANDSCAPE)
                        && root.hasWindowFocus() && root.isLaidOut() && !root.isLayoutRequested()
                        && list.isLaidOut() && !list.isLayoutRequested()
                        && header != null && header.isLaidOut() && !header.isLayoutRequested()
                        && list.getWidth() == root.getWidth() - root.getPaddingLeft() - root.getPaddingRight()
                        && list.getHeight() == root.getHeight() - root.getPaddingTop() - root.getPaddingBottom());
            });
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for browser orientation " + expected + " and completed layout; " + dimensions.get());
    }

    private static void assertDescendant(View child, View ancestor) {
        assertNotNull(child);
        ViewParent parent = child.getParent();
        while (parent != null && parent != ancestor) {
            parent = parent.getParent();
        }
        assertSame(ancestor, parent);
    }

    private static SmbBrowserViewModel model(SmbBrowserActivity activity) {
        return new ViewModelProvider(activity).get(SmbBrowserViewModel.class);
    }

    private static void awaitState(ActivityScenario<SmbBrowserActivity> scenario,
                                   Predicate<SmbBrowserViewModel.State> predicate) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        scenario.onActivity(activity -> model(activity).getState().observe(activity, state -> {
            if (predicate.test(state)) {
                ready.countDown();
            }
        }));
        assertTrue("Expected browser state", ready.await(5, TimeUnit.SECONDS));
    }

    private static final class DirectoryClient implements SmbClient {
        final Map<SmbLocation, List<SmbEntry>> listings = new ConcurrentHashMap<>();
        volatile CountDownLatch release = new CountDownLatch(0);
        volatile SmbException.Kind failure;
        volatile int calls;

        @Override
        public List<SmbEntry> list(SmbLocation location) throws IOException {
            calls++;
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IOException("Listing was not released by the test");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            if (failure != null) {
                throw new SmbException(failure);
            }
            return listings.getOrDefault(location, Collections.emptyList());
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}