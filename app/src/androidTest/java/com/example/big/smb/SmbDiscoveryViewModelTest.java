package com.example.big.smb;

import android.app.Application;

import androidx.lifecycle.ViewModelStore;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SmbDiscoveryViewModelTest {
    @Test
    public void deduplicatesEndpointsPrefersNamesAndSortsResults() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FakeScanner scanner = new FakeScanner();
            SmbDiscoveryViewModel model = model(scanner);
            model.start();
            SmbLocation nas = SmbLocation.parse("192.168.1.10");
            scanner.listener.found(nas.getHost(), nas);
            scanner.listener.found("Zebra NAS", nas);
            scanner.listener.found(nas.getHost(), nas);
            scanner.listener.found("Alpha NAS", SmbLocation.parse("192.168.1.11"));
            scanner.listener.found("Not a server", SmbLocation.parse("192.168.1.11/Media"));
            assertEquals(2, model.getState().getValue().servers.size());
            assertEquals("Alpha NAS", model.getState().getValue().servers.get(0).name);
            assertEquals("Zebra NAS", model.getState().getValue().servers.get(1).name);
            scanner.listener.finished(SmbDiscoveryViewModel.Status.COMPLETE);
            assertEquals(SmbDiscoveryViewModel.Status.COMPLETE, model.getState().getValue().status);
            scanner.listener.found("Late", SmbLocation.parse("192.168.1.12"));
            assertEquals(2, model.getState().getValue().servers.size());
            assertTrue(scanner.stops >= 2);
        });
    }

    @Test
    public void stopRefreshAndClearIgnoreStaleCallbacks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FakeScanner scanner = new FakeScanner();
            SmbDiscoveryViewModel model = model(scanner);
            ViewModelStore store = new ViewModelStore();
            store.put("discovery", model);
            model.start();
            SmbDiscoveryViewModel.Listener first = scanner.listener;
            first.found("NAS", SmbLocation.parse("192.168.1.10"));
            model.stop();
            first.finished(SmbDiscoveryViewModel.Status.ERROR);
            assertEquals(SmbDiscoveryViewModel.Status.STOPPED, model.getState().getValue().status);
            assertEquals(1, model.getState().getValue().servers.size());
            model.start();
            first.found("Stale", SmbLocation.parse("192.168.1.11"));
            first.finished(SmbDiscoveryViewModel.Status.COMPLETE);
            assertTrue(model.getState().getValue().servers.isEmpty());
            assertEquals(SmbDiscoveryViewModel.Status.SEARCHING, model.getState().getValue().status);
            store.clear();
            scanner.listener.found("Late", SmbLocation.parse("192.168.1.12"));
            assertEquals(SmbDiscoveryViewModel.Status.STOPPED, model.getState().getValue().status);
            assertTrue(model.getState().getValue().servers.isEmpty());
        });
    }

    @Test
    public void emptyNetworkErrorAndPermissionStatesCanBeRetried() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FakeScanner scanner = new FakeScanner();
            SmbDiscoveryViewModel model = model(scanner);
            for (SmbDiscoveryViewModel.Status status : new SmbDiscoveryViewModel.Status[]{
                    SmbDiscoveryViewModel.Status.COMPLETE, SmbDiscoveryViewModel.Status.NO_NETWORK,
                    SmbDiscoveryViewModel.Status.ERROR, SmbDiscoveryViewModel.Status.PERMISSION_REQUIRED}) {
                model.start();
                scanner.listener.finished(status);
                assertEquals(status, model.getState().getValue().status);
                assertTrue(model.getState().getValue().servers.isEmpty());
            }
            model.start();
            model.permissionRequired();
            scanner.listener.finished(SmbDiscoveryViewModel.Status.COMPLETE);
            assertEquals(SmbDiscoveryViewModel.Status.PERMISSION_REQUIRED, model.getState().getValue().status);
            model.start();
            assertEquals(SmbDiscoveryViewModel.Status.SEARCHING, model.getState().getValue().status);
            model.stop();
        });
    }

    private static SmbDiscoveryViewModel model(FakeScanner scanner) {
        Application application = ApplicationProvider.getApplicationContext();
        return new SmbDiscoveryViewModel(application, scanner);
    }

    private static final class FakeScanner implements SmbDiscoveryViewModel.Scanner {
        SmbDiscoveryViewModel.Listener listener;
        int stops;

        @Override
        public void start(SmbDiscoveryViewModel.Listener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() {
            stops++;
        }
    }
}