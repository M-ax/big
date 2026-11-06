package com.example.big;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.example.big.smb.JcifsSmbClient;
import com.example.big.smb.SmbBrowserViewModel;
import com.example.big.smb.SmbClient;
import com.example.big.smb.SmbDiscoveryProbe;
import com.example.big.smb.SmbEntry;
import com.example.big.smb.SmbException;
import com.example.big.smb.SmbFavoritesStore;
import com.example.big.smb.SmbLocation;
import com.example.big.smb.SmbSession;

import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.example.big.TouchActions.click;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Requires the loopback-only Samba fixture in tools/smb-test. Never use real credentials here. */
@RunWith(AndroidJUnit4.class)
public class SmbIntegrationTest {
    private String server;

    @Before
    public void setup() {
        server = InstrumentationRegistry.getArguments().getString("smbTestServer", "10.0.2.2:1445");
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                    "com.example.big", "android.permission.ACCESS_LOCAL_NETWORK");
        }
    }

    @Test
    public void discoveryProbeRecognizesSambaWithoutLoggingIn() throws Exception {
        SmbLocation location = SmbLocation.parse(server);
        Socket socket = new Socket();
        assertTrue(SmbDiscoveryProbe.isSmbServer(socket, InetAddress.getByName(location.getHost()),
                location.getPort(), 2000));
        assertTrue(socket.isClosed());
    }

    @Test
    public void accountLoginBrowsesSharesAndSpecialNamesAndPlaysWithRetainedSession() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SmbFavoritesStore favorites = new SmbFavoritesStore(context);
        SmbLocation folder = SmbLocation.parse(server + "/Media/People & Topics/");
        boolean alreadySaved = favorites.contains(folder);
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            login(server, "fixture-password");
            await(scenario, state -> !state.busy && state.location != null && state.location.isServerRoot());
            onView(withText("Media")).perform(click());
            awaitPath(scenario, "/Media/");
            onView(withText("People & Topics")).perform(click());
            awaitPath(scenario, "/Media/People & Topics/");
            AtomicReference<String> token = new AtomicReference<>();
            scenario.onActivity(activity -> {
                SmbBrowserViewModel model = model(activity);
                token.set(model.getSessionToken());
                List<SmbEntry> entries = model.getState().getValue().entries;
                assertEquals("Empty", entries.get(0).name);
                assertEquals("Clip #1 + 100% café.mp4", entries.get(1).name);
                assertFalse(entries.get(1).location.getUrl().contains("fixture-password"));
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertEquals(token.get(), model(activity).getSessionToken()));
            onView(withText("Empty")).perform(click());
            awaitPath(scenario, "/Media/People & Topics/Empty/");
            onView(withId(R.id.smb_empty)).check(matches(withText(R.string.smb_empty_folder)));
            clickBrowserControl(R.id.smb_up);
            awaitPath(scenario, "/Media/People & Topics/");
            onView(withId(R.id.smb_favorite)).perform(click());
            assertTrue(favorites.contains(folder));
            capture("big-favorite-browser.png");
            onView(withText("Clip #1 + 100% café.mp4")).perform(click());
            awaitPlayer(activity -> activity.findViewById(R.id.seek_bar).isEnabled()
                    && activity.findViewById(R.id.playback_status).getVisibility() == View.GONE);
            onView(withId(R.id.play_pause)).perform(click());
            onView(withId(R.id.media_title)).check(matches(withText("Clip #1 + 100% café.mp4")));
            awaitPlayer(activity -> activity.getString(R.string.play).contentEquals(
                    activity.findViewById(R.id.play_pause).getContentDescription()));
            java.util.concurrent.atomic.AtomicInteger start = new java.util.concurrent.atomic.AtomicInteger();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof PlayerActivity) {
                        start.set(((SeekBar) activity.findViewById(R.id.seek_bar)).getProgress());
                    }
                }
            });
            onView(withId(R.id.seek_forward)).perform(click());
            awaitPlayer(activity -> Math.abs(((SeekBar) activity.findViewById(R.id.seek_bar)).getProgress()
                    - start.get() - 333) < 40);
            onView(withId(R.id.seek_back)).perform(click());
            awaitPlayer(activity -> Math.abs(((SeekBar) activity.findViewById(R.id.seek_bar)).getProgress()
                    - start.get()) < 40);
            capture("big-player-icons.png");
            onView(withId(R.id.back)).perform(click());
            awaitPath(scenario, "/Media/People & Topics/");
            clickBrowserControl(R.id.smb_refresh);
            awaitPath(scenario, "/Media/People & Topics/");
            clickBrowserControl(R.id.smb_disconnect);
            onView(withId(R.id.smb_login)).check(matches(isDisplayed()));
            assertNull(SmbSession.find(token.get(), "smb://" + server + "/Media/a.mp4"));
            try (ActivityScenario<MainActivity> home = ActivityScenario.launch(MainActivity.class)) {
                String description = context.getString(R.string.smb_favorite_open, folder.getDisplayPath());
                onView(withContentDescription(description)).perform(scrollTo());
                capture("big-favorite-home.png");
                onView(withContentDescription(description)).perform(click());
                awaitFavoriteFolder(folder);
                onView(withText("Clip #1 + 100% café.mp4")).perform(scrollTo()).check(matches(isDisplayed()));
                clickBrowserControl(R.id.smb_back);
            }
        } finally {
            if (!alreadySaved) {
                favorites.remove(folder);
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof PlayerActivity) {
                        activity.finish();
                    }
                }
            });
        }
    }

    @Test
    public void wrongPasswordCanBeCorrectedAndDirectFolderLoginWorks() {
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            login(server + "/Media/People & Topics", "wrong-password");
            await(scenario, state -> !state.busy && state.error == SmbException.Kind.AUTHENTICATION);
            onView(withId(R.id.smb_status)).check(matches(withText(R.string.smb_auth_error)));
            onView(withId(R.id.smb_password)).perform(replaceText("fixture-password"), closeSoftKeyboard());
            onView(withId(R.id.smb_connect)).perform(scrollTo(), click());
            awaitPath(scenario, "/Media/People & Topics/");
            onView(withText("Clip #1 + 100% café.mp4")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void guestListingAndPermissionAndMissingFolderFailuresAreDistinct() throws Exception {
        try (SmbClient client = new JcifsSmbClient("", "", "", true)) {
            List<SmbEntry> entries = client.list(SmbLocation.parse(server + "/Public"));
            assertEquals("welcome.txt", entries.get(0).name);
        }
        try (SmbClient client = new JcifsSmbClient("smbtest", "fixture-password", "", false)) {
            try {
                client.list(SmbLocation.parse(server + "/Denied"));
                fail("Share permissions must be enforced");
            } catch (SmbException exception) {
                assertEquals(SmbException.Kind.ACCESS_DENIED, exception.kind);
            }
            try {
                client.list(SmbLocation.parse(server + "/Media/does-not-exist"));
                fail("Missing folders must not look empty");
            } catch (SmbException exception) {
                assertEquals(SmbException.Kind.NOT_FOUND, exception.kind);
            }
        }
    }

    @Test
    public void deniedNavigationAndRetryKeepTheCurrentShareList() {
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            login(server, "fixture-password");
            awaitPath(scenario, "/");
            onView(withText("Denied")).perform(click());
            await(scenario, state -> !state.busy && state.error == SmbException.Kind.ACCESS_DENIED);
            scenario.onActivity(activity -> assertTrue(model(activity).getState().getValue().location.isServerRoot()));
            onView(withId(R.id.smb_retry)).perform(click());
            await(scenario, state -> !state.busy && state.error == SmbException.Kind.ACCESS_DENIED);
            onView(withText("Media")).perform(click());
            awaitPath(scenario, "/Media/");
        }
    }

    @Test
    public void unreachableServerLeavesLoginUsable() {
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            login("127.0.0.1:1/Media", "fixture-password");
            await(scenario, state -> !state.busy && state.error == SmbException.Kind.NETWORK);
            onView(withId(R.id.smb_login)).check(matches(isDisplayed()));
            onView(withId(R.id.smb_status)).check(matches(withText(R.string.smb_network_error)));
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.smb_connect).isEnabled());
                assertNull(model(activity).getSessionToken());
            });
        }
    }

    @Test
    public void disconnectDiscardsAnInFlightConnectAndItsCredentials() {
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> {
                SmbBrowserViewModel model = model(activity);
                SmbLocation root = SmbLocation.parse(server);
                model.connect(root, new SmbSession(root, "smbtest", "fixture-password", "", false));
                assertTrue(model.getState().getValue().busy);
                model.disconnect();
                assertNull(model.getState().getValue().location);
            });
            // Queue a second request behind the first, then wait for it. The stale result must not win.
            scenario.onActivity(activity -> {
                SmbLocation publicShare = SmbLocation.parse(server + "/Public");
                model(activity).connect(publicShare, new SmbSession(publicShare, "", "", "", true));
            });
            awaitPath(scenario, "/Public/");
            scenario.onActivity(activity -> {
                assertNotNull(model(activity).getSessionToken());
                assertEquals("welcome.txt", model(activity).getState().getValue().entries.get(0).name);
            });
        }
    }

    private void capture(String name) throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalCacheDir(), name))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }

    private void login(String address, String password) {
        onView(withId(R.id.smb_address)).perform(scrollTo(), replaceText(address), closeSoftKeyboard());
        onView(withId(R.id.smb_username)).perform(scrollTo(), replaceText("smbtest"), closeSoftKeyboard());
        onView(withId(R.id.smb_password)).perform(scrollTo(), replaceText(password), closeSoftKeyboard());
        onView(withId(R.id.smb_connect)).perform(scrollTo(), click());
    }

    private void clickBrowserControl(int id) {
        onView(withId(R.id.smb_entries)).perform(new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return allOf(isAssignableFrom(ListView.class), isDisplayed());
            }

            @Override
            public String getDescription() {
                return "reveal the SMB quick-return controls with one upward list pixel";
            }

            @Override
            public void perform(UiController uiController, View view) {
                // Connected controls are outside the list even when docked at its top.
                ((ListView) view).scrollListBy(-1);
                uiController.loopMainThreadUntilIdle();
            }
        });
        onView(withId(id)).check(matches(isCompletelyDisplayed())).perform(click());
    }

    private void awaitFavoriteFolder(SmbLocation folder) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof SmbBrowserActivity) {
                        SmbBrowserViewModel.State state = model((SmbBrowserActivity) activity).getState().getValue();
                        ready.set(!state.busy && state.error == null && folder.equals(state.location));
                    }
                }
            });
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(100);
        }
        fail("Favorite must reconnect using its encrypted login after the old session is removed");
    }

    private SmbBrowserViewModel model(SmbBrowserActivity activity) {
        return new ViewModelProvider(activity).get(SmbBrowserViewModel.class);
    }

    private void awaitPath(ActivityScenario<SmbBrowserActivity> scenario, String path) {
        await(scenario, state -> !state.busy && state.location != null
                && state.location.getDecodedPath().equals(path) && state.error == null);
    }

    private void await(ActivityScenario<SmbBrowserActivity> scenario, Predicate<SmbBrowserViewModel.State> condition) {
        long deadline = SystemClock.uptimeMillis() + 25000;
        AtomicReference<SmbBrowserViewModel.State> state = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> state.set(model(activity).getState().getValue()));
            if (condition.test(state.get())) {
                return;
            }
            SystemClock.sleep(100);
        }
        fail("SMB state did not settle as expected (error=" + state.get().error
                + "). Start the tools/smb-test fixture before running integration tests.");
    }

    private void awaitPlayer(Predicate<PlayerActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 20000;
        AtomicReference<PlayerActivity> player = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < deadline) {
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof PlayerActivity) {
                        player.set((PlayerActivity) activity);
                        ready.set(condition.test((PlayerActivity) activity));
                    }
                }
            });
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(100);
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> fail("SMB playback did not start/seek: "
                + (player.get() == null ? "no player" : ((TextView) player.get()
                .findViewById(R.id.playback_status)).getText())));
    }
}