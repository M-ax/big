package com.example.big;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.smb.SmbBrowserAdapter;
import com.example.big.smb.SmbBrowserListView;
import com.example.big.smb.SmbBrowserViewModel;
import com.example.big.smb.SmbClient;
import com.example.big.smb.SmbEntry;
import com.example.big.smb.SmbException;
import com.example.big.smb.SmbFavoritesBrowserFixture;
import com.example.big.smb.SmbLocation;
import com.example.big.smb.SmbScrollPosition;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SmbBrowserScrollTest {
    @Before
    public void allowLocalNetwork() {
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                    "com.example.big", "android.permission.ACCESS_LOCAL_NETWORK");
        }
    }

    @Test
    public void nestedFoldersBackUpRefreshAndRotationRestoreBeforeTheFirstDraw() throws Exception {
        SmbLocation folder = SmbLocation.parse("scroll-anchors-test/Media");
        FolderClient client = new FolderClient();
        List<SmbEntry> parentRows = client.list(folder);
        SmbLocation child = parentRows.get(30).location;
        SmbLocation grandchild = client.list(child).get(40).location;
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> SmbFavoritesBrowserFixture.connect(activity, client, folder, parentRows));
            idle();
            select(scenario, 31, -17);
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.smb_entries);
                list.performItemClick(list.getChildAt(0), 31, 31);
            });
            await(scenario, state -> child.equals(state.location) && !state.busy);
            idle();
            select(scenario, 41, -23);
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.smb_entries);
                list.performItemClick(list.getChildAt(0), 41, 41);
            });
            await(scenario, state -> grandchild.equals(state.location) && !state.busy);
            idle();
            select(scenario, 51, -11);
            try (FirstListingDraw draw = new FirstListingDraw(scenario, child, 41, -23)) {
                scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
                draw.await();
            }
            try (FirstListingDraw draw = new FirstListingDraw(scenario, folder, 31, -17)) {
                scenario.onActivity(activity -> clickHeader(activity, R.id.smb_up));
                draw.await();
            }
            scenario.recreate();
            idle();
            assertPosition(scenario, 31, -17);
            try (FirstListingDraw draw = new FirstListingDraw(scenario, child, 41, -23)) {
                scenario.onActivity(activity -> model(activity).navigate(child));
                draw.await();
            }
            try (FirstListingDraw draw = new FirstListingDraw(scenario, grandchild, 51, -11)) {
                scenario.onActivity(activity -> model(activity).navigate(grandchild));
                draw.await();
            }
            try (FirstListingDraw draw = new FirstListingDraw(scenario, grandchild, 51, -11)) {
                scenario.onActivity(activity -> clickHeader(activity, R.id.smb_refresh));
                draw.await();
            }
            try (FirstListingDraw draw = new FirstListingDraw(scenario, grandchild, 51, -11)) {
                scenario.recreate();
                draw.await();
            }
            try (FirstListingDraw draw = new FirstListingDraw(scenario, grandchild, 51, -11)) {
                scenario.onActivity(activity -> activity.setRequestedOrientation(
                        activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                draw.await();
            }
        }
    }

    @Test
    public void loadingFailureRetryAndInsertedRowsPreserveTheOriginalAnchor() throws Exception {
        SmbLocation folder = SmbLocation.parse("scroll-retry-test/Media");
        FolderClient client = new FolderClient();
        List<SmbEntry> parentRows = client.list(folder);
        SmbLocation child = parentRows.get(30).location;
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> SmbFavoritesBrowserFixture.connect(activity, client, folder, parentRows));
            idle();
            select(scenario, 31, -17);
            client.release = new CountDownLatch(1);
            client.failure = SmbException.Kind.NETWORK;
            scenario.onActivity(activity -> model(activity).navigate(child));
            idle();
            assertPosition(scenario, 31, -17);
            scenario.recreate();
            idle();
            assertPosition(scenario, 31, -17);
            client.release.countDown();
            await(scenario, state -> state.error == SmbException.Kind.NETWORK);
            idle();
            assertPosition(scenario, 31, -17);
            client.failure = null;
            scenario.onActivity(activity -> clickHeader(activity, R.id.smb_retry));
            await(scenario, state -> child.equals(state.location) && !state.busy);
            idle();
            select(scenario, 21, -9);
            try (FirstListingDraw draw = new FirstListingDraw(scenario, folder, 31, -17)) {
                scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
                draw.await();
            }
            List<SmbEntry> changed = new ArrayList<>(parentRows);
            changed.add(new SmbEntry("Added before anchor", true, folder.child("Added before anchor", true), 0));
            client.listings.put(folder, changed);
            try (FirstListingDraw draw = new FirstListingDraw(scenario, folder, 32, -17)) {
                scenario.onActivity(activity -> model(activity).navigate(folder));
                draw.await();
            }
        } finally {
            client.release.countDown();
        }
    }

    @Test
    public void quickReturnedTitleAndExactOffsetSurviveRecreation() throws Exception {
        SmbLocation folder = SmbLocation.parse("scroll-title-test/Media");
        FolderClient client = new FolderClient();
        List<SmbEntry> entries = client.list(folder);
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> SmbFavoritesBrowserFixture.connect(activity, client, folder, entries));
            idle();
            select(scenario, 31, -17);
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.smb_entries);
                assertEquals(View.GONE, activity.findViewById(R.id.smb_quick_title).getVisibility());
                list.scrollListBy(-1);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.smb_quick_title).getVisibility());
            });
            assertQuickControls();
            try (FirstListingDraw draw = new FirstListingDraw(scenario, folder, 31, -16)) {
                scenario.recreate();
                draw.await();
            }
            assertQuickControls();
            try (FirstListingDraw draw = new FirstListingDraw(scenario, folder, 31, -16)) {
                scenario.onActivity(activity -> activity.setRequestedOrientation(
                        activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                draw.await();
            }
            assertQuickControls();
            scenario.onActivity(activity -> {
                TextView title = activity.findViewById(R.id.smb_title);
                assertEquals("SMB scroll-title-test", title.getText().toString());
                assertEquals(View.VISIBLE, title.getVisibility());
                ((ListView) activity.findViewById(R.id.smb_entries)).scrollListBy(1);
                assertEquals(View.GONE, activity.findViewById(R.id.smb_quick_title).getVisibility());
            });
        }
    }

    @Test
    public void savedStateKeepsEveryCanonicalLocationWithoutRequiringALiveSession() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SmbBrowserViewModel original = new SmbBrowserViewModel();
            SmbLocation first = SmbLocation.parse("SMB://NAS:445/Media/Folder%20A/");
            SmbLocation second = SmbLocation.parse("nas/Other/Folder%20A");
            SmbLocation port = SmbLocation.parse("nas:1445/Media/Folder%20A");
            original.saveScrollPosition(first, new SmbScrollPosition(31, -17, "first", false));
            original.saveScrollPosition(second, new SmbScrollPosition(11, -6, "second", true));
            original.saveScrollPosition(port, new SmbScrollPosition(8, -4, "third", false));
            SmbBrowserViewModel restored = new SmbBrowserViewModel();
            restored.restoreScrollPositions(original.saveScrollPositions());
            assertEquals(31, restored.getScrollPosition(SmbLocation.parse("nas/Media/Folder%20A")).position);
            assertEquals(-17, restored.getScrollPosition(first).top);
            assertEquals("second", restored.getScrollPosition(second).anchor);
            assertEquals(8, restored.getScrollPosition(port).position);
            assertNull(restored.getSessionToken());
            assertNull(restored.getState().getValue().location);
        });
    }

    @Test
    public void disabledSeparatorAndPartiallyScrolledHeaderRestoreExactly() throws Exception {
        SmbLocation folder = SmbLocation.parse("scroll-disabled-test/Media");
        FolderClient client = new FolderClient();
        List<SmbEntry> entries = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            String name = String.format(Locale.ROOT, "%02d", index);
            entries.add(new SmbEntry(name + ".mp4", false, folder.child(name + ".mp4", false), 1));
            entries.add(new SmbEntry(name + ".txt", false, folder.child(name + ".txt", false), 1));
        }
        client.listings.put(folder, entries);
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> SmbFavoritesBrowserFixture.connect(activity, client, folder, entries));
            idle();
            scenario.onActivity(activity -> {
                SmbBrowserListView list = activity.findViewById(R.id.smb_entries);
                assertFalse(list.getAdapter().isEnabled(61));
                list.restoreBeforeDraw(61, -3, () -> { });
            });
            idle();
            assertPosition(scenario, 61, -3);
            try (FirstListingDraw draw = new FirstListingDraw(scenario, folder, 61, -3)) {
                scenario.onActivity(activity -> model(activity).navigate(folder));
                draw.await();
            }
            scenario.onActivity(activity -> ((SmbBrowserListView) activity.findViewById(R.id.smb_entries))
                    .restoreBeforeDraw(0, -12, () -> { }));
            idle();
            assertPosition(scenario, 0, -12);
            try (FirstListingDraw draw = new FirstListingDraw(scenario, folder, 0, -12)) {
                scenario.onActivity(activity -> model(activity).navigate(folder));
                draw.await();
            }
        }
    }

    @Test
    public void rowRecyclingResetsIconsBadgesAndSeparatorClicksCannotOpenTheWrongFile() {
        SmbLocation folder = SmbLocation.parse("scroll-rows-test/Media");
        List<SmbEntry> entries = Arrays.asList(
                new SmbEntry("0.txt", false, folder.child("0.txt", false), 20),
                new SmbEntry("movie.MP4", false, folder.child("movie.MP4", false), 30),
                new SmbEntry("song.flac", false, folder.child("song.flac", false), 40),
                new SmbEntry("folder.mp4", true, folder.child("folder.mp4", true), 0));
        try (ActivityScenario<SmbBrowserActivity> scenario = ActivityScenario.launch(SmbBrowserActivity.class)) {
            scenario.onActivity(activity -> {
                SmbFavoritesBrowserFixture.connect(activity, new FolderClient(), folder, entries);
                ListView list = activity.findViewById(R.id.smb_entries);
                SmbBrowserAdapter adapter = new SmbBrowserAdapter(activity);
                adapter.submit(entries, false);
                View row = adapter.getView(1, null, list);
                assertEquals(SmbEntry.Type.VIDEO, row.findViewById(R.id.smb_row_icon).getTag());
                assertEquals("MP4", ((TextView) row.findViewById(R.id.smb_row_extension)).getText().toString());
                row = adapter.getView(2, row, list);
                assertEquals(SmbEntry.Type.AUDIO, row.findViewById(R.id.smb_row_icon).getTag());
                assertEquals("FLAC", ((TextView) row.findViewById(R.id.smb_row_extension)).getText().toString());
                row = adapter.getView(0, row, list);
                assertEquals(SmbEntry.Type.DIRECTORY, row.findViewById(R.id.smb_row_icon).getTag());
                assertEquals(View.GONE, row.findViewById(R.id.smb_row_extension).getVisibility());
                row = adapter.getView(4, row, list);
                assertEquals(SmbEntry.Type.FILE, row.findViewById(R.id.smb_row_icon).getTag());
                assertEquals(View.GONE, row.findViewById(R.id.smb_row_extension).getVisibility());
                assertFalse(list.getAdapter().isEnabled(4));
                list.performItemClick(adapter.getView(3, null, list), 4, 4);
                assertEquals(folder, model(activity).getState().getValue().location);
                list.performItemClick(row, 5, 5);
            });
            onView(withText(R.string.smb_unknown_file)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.cancel)).inRoot(isDialog()).perform(click());
        }
    }

    private static void assertQuickControls() {
        for (int id : new int[]{R.id.smb_title, R.id.smb_back, R.id.smb_path,
                R.id.smb_up, R.id.smb_refresh, R.id.smb_disconnect}) {
            onView(withId(id)).check(matches(isCompletelyDisplayed()));
        }
    }

    private static void clickHeader(SmbBrowserActivity activity, int id) {
        View control = activity.findViewById(id);
        if (control == null) {
            ListView list = activity.findViewById(R.id.smb_entries);
            control = list.getAdapter().getView(0, null, list).findViewById(id);
        }
        control.performClick();
    }

    private static void select(ActivityScenario<SmbBrowserActivity> scenario, int position, int top) {
        scenario.onActivity(activity -> ((ListView) activity.findViewById(R.id.smb_entries))
                .setSelectionFromTop(position, top));
        idle();
        assertPosition(scenario, position, top);
    }

    private static void assertPosition(ActivityScenario<SmbBrowserActivity> scenario, int position, int top) {
        scenario.onActivity(activity -> {
            ListView list = activity.findViewById(R.id.smb_entries);
            assertEquals(position, list.getFirstVisiblePosition());
            assertEquals(top, list.getChildAt(0).getTop() - list.getPaddingTop());
        });
    }

    private static SmbBrowserViewModel model(SmbBrowserActivity activity) {
        return new ViewModelProvider(activity).get(SmbBrowserViewModel.class);
    }

    private static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void await(ActivityScenario<SmbBrowserActivity> scenario,
                              Predicate<SmbBrowserViewModel.State> predicate) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        scenario.onActivity(activity -> model(activity).getState().observe(activity, state -> {
            if (predicate.test(state)) {
                ready.countDown();
            }
        }));
        assertTrue("Expected browser state", ready.await(5, TimeUnit.SECONDS));
    }

    private static final class FolderClient implements SmbClient {
        final Map<SmbLocation, List<SmbEntry>> listings = new ConcurrentHashMap<>();
        volatile CountDownLatch release = new CountDownLatch(0);
        volatile SmbException.Kind failure;

        @Override
        public List<SmbEntry> list(SmbLocation location) throws IOException {
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Test listing was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            if (failure != null) {
                throw new SmbException(failure);
            }
            return listings.computeIfAbsent(location, folder -> {
                List<SmbEntry> rows = new ArrayList<>();
                for (int index = 0; index < 80; index++) {
                    String name = String.format(Locale.ROOT, "Folder %02d", index);
                    rows.add(new SmbEntry(name, true, folder.child(name, true), 0));
                }
                return rows;
            });
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    /** Checks the first actual drawing traversal, including a newly recreated activity. */
    private static final class FirstListingDraw implements Application.ActivityLifecycleCallbacks, AutoCloseable {
        private final Application application = ApplicationProvider.getApplicationContext();
        private final CountDownLatch drawn = new CountDownLatch(1);
        private final AtomicBoolean seen = new AtomicBoolean();
        private final AtomicReference<String> actual = new AtomicReference<>();
        private final List<Runnable> detach = new ArrayList<>();
        private final SmbLocation location;
        private final String expected;
        private SmbBrowserViewModel.State initial;

        FirstListingDraw(ActivityScenario<SmbBrowserActivity> scenario, SmbLocation location, int position, int top) {
            this.location = location;
            expected = position + ":" + top;
            application.registerActivityLifecycleCallbacks(this);
            scenario.onActivity(activity -> {
                initial = model(activity).getState().getValue();
                attach(activity, false);
            });
        }

        private void attach(SmbBrowserActivity activity, boolean recreated) {
            SmbBrowserListView list = activity.findViewById(R.id.smb_entries);
            ViewTreeObserver.OnDrawListener listener = () -> {
                SmbBrowserViewModel.State state = model(activity).getState().getValue();
                if ((recreated || state != initial) && !state.busy && state.error == null
                        && location.equals(state.location) && seen.compareAndSet(false, true)) {
                    actual.set(list.getFirstVisiblePosition() + ":"
                            + (list.getChildCount() == 0 ? "missing" : list.getChildAt(0).getTop() - list.getPaddingTop()));
                    drawn.countDown();
                }
            };
            list.getViewTreeObserver().addOnDrawListener(listener);
            detach.add(() -> {
                if (list.getViewTreeObserver().isAlive()) {
                    list.getViewTreeObserver().removeOnDrawListener(listener);
                }
            });
        }

        void await() throws InterruptedException {
            assertTrue("Expected first listing draw", drawn.await(5, TimeUnit.SECONDS));
            assertEquals("The first drawn frame must already have its saved offset", expected, actual.get());
        }

        @Override public void onActivityPostCreated(Activity activity, Bundle saved) {
            if (activity instanceof SmbBrowserActivity) {
                attach((SmbBrowserActivity) activity, true);
            }
        }
        @Override public void onActivityCreated(Activity activity, Bundle saved) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) { }
        @Override public void onActivityDestroyed(Activity activity) { }

        @Override
        public void close() {
            application.unregisterActivityLifecycleCallbacks(this);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Runnable action : detach) {
                    action.run();
                }
            });
        }
    }
}