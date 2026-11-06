package com.example.big.smb;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.big.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.KeyGenerator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SmbCredentialsStoreTest {
    private static final String ALIAS = "com.example.big.smb.credentials.test";
    private final SmbLocation folder = SmbLocation.parse("SERVER:445/Media/Exact%20Folder/100%25/");
    private SharedPreferences preferences;
    private SmbCredentialsStore store;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences("smb_credentials_test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        deleteKey();
        store = new SmbCredentialsStore(preferences, ALIAS);
    }

    @After
    public void tearDown() throws Exception {
        preferences.edit().clear().commit();
        deleteKey();
    }

    @Test
    public void encryptsEveryFieldWithFreshIvAndSurvivesSessionRemoval() throws Exception {
        SmbSession session = new SmbSession(folder, "unique-user", "private-password", "PRIVATE-DOMAIN", false);
        String token = session.register();
        assertTrue(store.save(folder, session));
        String first = preferences.getString(folder.getUrl(), null);
        assertNotNull(first);
        byte[] record = Base64.decode(first, Base64.NO_WRAP);
        String raw = new String(record, StandardCharsets.ISO_8859_1);
        Context context = ApplicationProvider.getApplicationContext();
        String disk = new String(Files.readAllBytes(new File(context.getApplicationInfo().dataDir,
                "shared_prefs/smb_credentials_test.xml").toPath()), StandardCharsets.UTF_8);
        for (String secret : new String[]{"unique-user", "private-password", "PRIVATE-DOMAIN", token}) {
            assertFalse(first.contains(secret));
            assertFalse(raw.contains(secret));
            assertFalse(disk.contains(secret));
        }
        SmbSession.remove(token);
        SmbSession restored = new SmbCredentialsStore(preferences, ALIAS).load(folder);
        assertNotNull(restored);
        assertEquals(session.mediaOptions(), restored.mediaOptions());
        assertTrue(store.save(folder, session));
        String second = preferences.getString(folder.getUrl(), null);
        assertNotEquals(first, second);
        assertFalse(Arrays.equals(Arrays.copyOfRange(record, 1, 13),
                Arrays.copyOfRange(Base64.decode(second, Base64.NO_WRAP), 1, 13)));
    }

    @Test
    public void exactCanonicalFolderAndAuthorityAreAuthenticated() {
        assertTrue(store.save(folder, new SmbSession(folder, "alice", "password", "", false)));
        assertNotNull(store.load(SmbLocation.parse("server/Media/Exact Folder/100%25")));
        String record = preferences.getString(folder.getUrl(), null);
        for (String address : new String[]{"server/Media/Other", "server/Media", "server/Other",
                "other-server/Media/Exact%20Folder/100%25", "server:1445/Media/Exact%20Folder/100%25",
                "server/Media/exact%20Folder/100%25"}) {
            SmbLocation other = SmbLocation.parse(address);
            assertNull(store.load(other));
            preferences.edit().putString(other.getUrl(), record).commit();
            assertNull(store.load(other));
            assertFalse(preferences.contains(other.getUrl()));
        }
        assertNotNull(store.load(folder));
        assertFalse(store.save(SmbLocation.parse("other-server/Media"),
                new SmbSession(folder, "alice", "password", "", false)));
    }

    @Test
    public void tamperingAndMalformedRecordsAreDiscarded() {
        assertTrue(store.save(folder, new SmbSession(folder, "alice", "password", "", false)));
        byte[] record = Base64.decode(preferences.getString(folder.getUrl(), ""), Base64.NO_WRAP);
        record[record.length - 1] ^= 1;
        preferences.edit().putString(folder.getUrl(), Base64.encodeToString(record, Base64.NO_WRAP)).commit();
        assertNull(store.load(folder));
        assertFalse(preferences.contains(folder.getUrl()));
        preferences.edit().putInt(folder.getUrl(), 42).commit();
        assertNull(store.load(folder));
        assertFalse(preferences.contains(folder.getUrl()));
        preferences.edit().putString(folder.getUrl(), "not a credential record").commit();
        assertNull(store.load(folder));
    }

    @Test
    public void missingOrReplacedKeyFailsClosedWithoutRecreatingItOnRead() throws Exception {
        assertTrue(store.save(folder, new SmbSession(folder, "alice", "password", "", false)));
        String oldRecord = preferences.getString(folder.getUrl(), null);
        deleteKey();
        assertNull(store.load(folder));
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        assertFalse(keys.containsAlias(ALIAS));
        assertTrue(store.save(folder, new SmbSession(folder, "bob", "new-password", "", false)));
        preferences.edit().putString(folder.getUrl(), oldRecord).commit();
        assertNull(store.load(folder));
        assertFalse(preferences.contains(folder.getUrl()));
    }

    @Test
    public void guestNeverRetainsTypedCredentialsAndRemovalIsPermanent() {
        assertTrue(store.save(folder, new SmbSession(folder, "ignored-user", "ignored-password", "ignored-domain", true)));
        assertEquals(Arrays.asList(":smb-user=Guest", ":smb-pwd=", ":smb-domain="),
                store.load(folder).mediaOptions());
        store.remove(folder);
        assertNull(new SmbCredentialsStore(preferences, ALIAS).load(folder));
        assertFalse(store.save(SmbLocation.parse("server"), new SmbSession(folder, "", "", "", true)));
    }

    @Test
    public void encryptionFailureKeepsFolderFavoriteButDiscardsOldLogin() throws Exception {
        SmbSession session = new SmbSession(folder, "alice", "password", "", false);
        assertTrue(store.save(folder, session));
        deleteKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .build());
        generator.generateKey();
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences folders = context.getSharedPreferences("smb_credentials_folders_test", Context.MODE_PRIVATE);
        try {
            SmbFavoritesStore favorites = new SmbFavoritesStore(folders, store);
            favorites.add(folder);
            assertFalse(favorites.saveCredentials(folder, session));
            assertTrue(favorites.contains(folder));
            assertFalse(preferences.contains(folder.getUrl()));
            assertNull(store.load(folder));
        } finally {
            folders.edit().clear().commit();
        }
    }

    @Test
    public void credentialsAndTemporaryPreferenceBackupAreExcludedFromAllTransfers() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        for (int resource : new int[]{R.xml.backup_rules, R.xml.data_extraction_rules}) {
            Set<String> excluded = new HashSet<>();
            try (XmlResourceParser parser = context.getResources().getXml(resource)) {
                String section = "full-backup-content";
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.getEventType() != XmlPullParser.START_TAG) {
                        continue;
                    }
                    if ("cloud-backup".equals(parser.getName()) || "device-transfer".equals(parser.getName())) {
                        section = parser.getName();
                    }
                    if ("exclude".equals(parser.getName()) && "sharedpref".equals(parser.getAttributeValue(null, "domain"))) {
                        excluded.add(section + ":" + parser.getAttributeValue(null, "path"));
                    }
                }
            }
            String[] sections = resource == R.xml.backup_rules ? new String[]{"full-backup-content"}
                    : new String[]{"cloud-backup", "device-transfer"};
            for (String section : sections) {
                assertTrue(excluded.contains(section + ":smb_credentials.xml"));
                assertTrue(excluded.contains(section + ":smb_credentials.xml.bak"));
            }
        }
    }

    @Test
    public void unavailableStorageRevokesKeyRatherThanLeavingRecoverableOldLogin() throws Exception {
        assertTrue(store.save(folder, new SmbSession(folder, "alice", "password", "", false)));
        new SmbCredentialsStore(null, ALIAS).remove(folder);
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        assertFalse(keys.containsAlias(ALIAS));
        assertNull(store.load(folder));
    }

    private static void deleteKey() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        keys.deleteEntry(ALIAS);
    }
}