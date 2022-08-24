package com.bugsnag.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class NativeInterfaceTest {

    private Client client;

    @Before
    public void setUp() {
        client = BugsnagTestUtils.generateClient();
    }

    @After
    public void tearDown() {
        client.close();
    }

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void getMetadata() {
        NativeInterface.setClient(client);
        assertNotSame(client.metadataState.getMetadata().toMap(), NativeInterface.getMetadata());
    }

    @Test
    public void configUsagePersistenceDefault() throws IOException {
        final Map<String, Object> expected = new HashMap<String, Object>() {
            {
                put("launchDurationMillis", (long)5000);
                put("logger", true);
            }
        };

        Client client = BugsnagTestUtils.generateClient();
        NativeInterface.setClient(client);
        Configuration config = new Configuration("x");
        File persistencePath = tempFolder.newFolder();
        NativeInterface.persistConfigDifferences(persistencePath,
                config.impl.getConfigDifferences());
        NativeInterface.loadPreviousConfigDifferences(persistencePath);
        Map<String, Object> actual = NativeInterface.previousConfigDifferences;
        assertEquals(expected, actual);
    }

    @Test
    public void configUsagePersistence() throws IOException {
        final Map<String, Object> expected = new HashMap<String, Object>() {
            {
                put("autoTrackSessions", false);
                put("enabledBreadcrumbTypes", "navigation,state");
                put("enabledErrorTypes", "ndkCrashes,unhandledRejections");
                put("launchDurationMillis", 5000L);
                put("logger", true);
                put("maxBreadcrumbs", 30L);
                put("maxPersistedSessions", 10L);
            }
        };

        Configuration config = new Configuration("x");
        ErrorTypes errorTypes = new ErrorTypes();
        errorTypes.setAnrs(false);
        errorTypes.setUnhandledExceptions(false);
        config.setEnabledErrorTypes(errorTypes);
        config.setAutoTrackSessions(false);
        config.setMaxBreadcrumbs(30);
        config.setMaxPersistedSessions(10);
        config.setEnabledBreadcrumbTypes(new HashSet<BreadcrumbType>() {{
                add(BreadcrumbType.STATE);
                add(BreadcrumbType.NAVIGATION);
            }
        });
        config.addOnBreadcrumb(new OnBreadcrumbCallback() {
            @Override
            public boolean onBreadcrumb(@NonNull Breadcrumb breadcrumb) {
                return false;
            }
        });
        config.addOnBreadcrumb(new OnBreadcrumbCallback() {
            @Override
            public boolean onBreadcrumb(@NonNull Breadcrumb breadcrumb) {
                return false;
            }
        });
        config.addOnError(new OnErrorCallback() {
            @Override
            public boolean onError(@NonNull Event event) {
                return false;
            }
        });
        File persistencePath = tempFolder.newFolder();
        NativeInterface.persistConfigDifferences(persistencePath,
                config.impl.getConfigDifferences());
        NativeInterface.loadPreviousConfigDifferences(persistencePath);
        Map<String, Object> actual = NativeInterface.previousConfigDifferences;
        assertEquals(expected, actual);
    }

    @Test
    public void addToTab() {
        Client client = BugsnagTestUtils.generateClient();
        NativeInterface.setClient(client);
        NativeInterface.addMetadata("app", "buildno", "0.1");
        NativeInterface.addMetadata("app", "args", "-print 1");
        NativeInterface.addMetadata("info", "cache", false);

        Map<String, Object> metadata = NativeInterface.getMetadata();
        @SuppressWarnings("unchecked")
        Map<String, Object> app = (Map<String, Object>)metadata.get("app");
        assertSame("0.1", app.get("buildno"));
        assertSame("-print 1", app.get("args"));
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>)metadata.get("info");
        assertSame(false, info.get("cache"));
    }

    @Test
    public void clearTab() {
        Client client = BugsnagTestUtils.generateClient();
        NativeInterface.setClient(client);
        NativeInterface.addMetadata("app", "buildno", "0.1");
        NativeInterface.addMetadata("app", "args", "-print 1");
        NativeInterface.addMetadata("info", "cache", false);
        NativeInterface.clearMetadata("info", null);

        Map<String, Object> metadata = NativeInterface.getMetadata();
        @SuppressWarnings("unchecked")
        Map<String, Object> app = (Map<String, Object>)metadata.get("app");
        assertSame("0.1", app.get("buildno"));
        assertSame("-print 1", app.get("args"));
        assertNull(metadata.get("info"));
    }
}
