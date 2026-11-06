package com.example.big;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.smb.SmbDiscoveryViewModel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class SmbDiscoveryActivityTest {
    @Before
    public void allowLocalNetwork() {
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                    "com.example.big", "android.permission.ACCESS_LOCAL_NETWORK");
        }
    }

    @Test
    public void autoStartsRetainsAcrossRotationAndStopsInBackground() {
        try (ActivityScenario<SmbDiscoveryActivity> scenario = ActivityScenario.launch(SmbDiscoveryActivity.class)) {
            AtomicReference<SmbDiscoveryViewModel> original = new AtomicReference<>();
            scenario.onActivity(activity -> {
                original.set(model(activity));
                assertEquals(SmbDiscoveryViewModel.Status.SEARCHING, model(activity).getState().getValue().status);
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertSame(original.get(), model(activity)));
            onView(withId(R.id.smb_discovery_stop)).perform(scrollTo(), click());
            onView(withId(R.id.smb_discovery_status)).check(matches(withText(R.string.smb_discovery_stopped)));
            onView(withId(R.id.smb_discovery_refresh)).check(matches(isEnabled())).perform(click());
            scenario.moveToState(Lifecycle.State.CREATED);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertEquals(
                    SmbDiscoveryViewModel.Status.STOPPED, original.get().getState().getValue().status));
            scenario.moveToState(Lifecycle.State.RESUMED);
            onView(withId(R.id.smb_add_share)).check(matches(isEnabled()));
        }
    }

    @Test
    public void discoversAdvertisedServerAndPassesAddressToLogin() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        NsdManager nsd = context.getSystemService(NsdManager.class);
        NsdServiceInfo service = new NsdServiceInfo();
        service.setServiceName("Big discovery test " + SystemClock.elapsedRealtime());
        service.setServiceType("_smb._tcp.");
        service.setNetwork(context.getSystemService(ConnectivityManager.class).getActiveNetwork());
        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<String> serviceName = new AtomicReference<>();
        AtomicReference<Integer> error = new AtomicReference<>();
        NsdManager.RegistrationListener registration = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int code) {
                error.set(code);
                registered.countDown();
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                serviceName.set(info.getServiceName());
                registered.countDown();
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int code) { }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) { }
        };
        try (ServerSocket socket = new ServerSocket(0)) {
            service.setPort(socket.getLocalPort());
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration);
            try {
                assertTrue("NSD registration timed out", registered.await(10, TimeUnit.SECONDS));
                assertNull("NSD registration failed", error.get());
                try (ActivityScenario<SmbDiscoveryActivity> scenario = ActivityScenario.launch(SmbDiscoveryActivity.class)) {
                    SmbDiscoveryViewModel.Server server = awaitServer(scenario, serviceName.get());
                    assertEquals(socket.getLocalPort(), server.location.getPort());
                    scenario.recreate();
                    onView(withText(server.name)).perform(click());
                    onView(withId(R.id.smb_title)).check(matches(withText("Add SMB Share")))
                            .check(matches(isDisplayed()));
                    onView(withId(R.id.smb_address)).check(matches(withText(server.location.getUrl())));
                    onView(withId(R.id.smb_password)).check(matches(withText("")));
                    onView(withId(R.id.smb_back)).perform(click());
                    onView(withText(server.name)).check(matches(isDisplayed()));
                    onView(withId(R.id.smb_add_share)).perform(click());
                    onView(withId(R.id.smb_address)).check(matches(withText("")));
                    onView(withId(R.id.smb_back)).perform(click());
                }
            } finally {
                nsd.unregisterService(registration);
            }
        }
    }

    private static SmbDiscoveryViewModel.Server awaitServer(ActivityScenario<SmbDiscoveryActivity> scenario,
                                                            String name) {
        AtomicReference<SmbDiscoveryViewModel.Server> found = new AtomicReference<>();
        long until = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < until) {
            scenario.onActivity(activity -> {
                for (SmbDiscoveryViewModel.Server server : model(activity).getState().getValue().servers) {
                    if (server.name.equals(name)) {
                        found.set(server);
                    }
                }
            });
            if (found.get() != null) {
                return found.get();
            }
            SystemClock.sleep(100);
        }
        fail("Advertised SMB server was not discovered");
        return null;
    }

    private static SmbDiscoveryViewModel model(SmbDiscoveryActivity activity) {
        return new ViewModelProvider(activity).get(SmbDiscoveryViewModel.class);
    }
}