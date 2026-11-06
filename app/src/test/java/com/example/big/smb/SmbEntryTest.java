package com.example.big.smb;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SmbEntryTest {
    @Test
    public void identifiesMediaWithoutMistakingDirectoryExtensionsForFiles() {
        assertEquals(SmbEntry.Type.VIDEO, entry("Movie.MkV", false).getType());
        assertEquals("MKV", entry("Movie.MkV", false).getExtension());
        assertEquals(SmbEntry.Type.AUDIO, entry("Song.FLAC", false).getType());
        assertEquals(SmbEntry.Type.DIRECTORY, entry("Album.mp4", true).getType());
        assertEquals(SmbEntry.Type.FILE, entry("notes.txt", false).getType());
        assertFalse(SmbEntry.isPlayable(null));
        assertFalse(SmbEntry.isPlayable("movie.mp4.txt"));
        assertFalse(SmbEntry.isPlayable("movie."));
        assertFalse(SmbEntry.isPlayable("mp4"));
        assertTrue(SmbEntry.isPlayable("movie.MP4"));
    }

    @Test
    public void groupsRowsAndKeepsTheSeparatorOutOfEntryClickMapping() {
        SmbEntry directory = entry("z.mp4", true);
        SmbEntry video = entry("B.MKV", false);
        SmbEntry audio = entry("a.mp3", false);
        SmbEntry other = entry("0.txt", false);
        SmbBrowserRows rows = new SmbBrowserRows(Arrays.asList(other, video, directory, audio));
        assertEquals(5, rows.size());
        assertEquals(directory, rows.get(0));
        assertEquals(audio, rows.get(1));
        assertEquals(video, rows.get(2));
        assertNull(rows.get(3));
        assertEquals(other, rows.get(4));
        assertEquals(4, rows.indexOf(other.location.getUrl()));
        assertEquals(-1, rows.indexOf("missing"));
    }

    @Test
    public void emptyGroupsDoNotProduceOrphanOrDuplicateSeparators() {
        assertEquals(0, new SmbBrowserRows(Collections.emptyList()).size());
        assertEquals(1, new SmbBrowserRows(Collections.singletonList(entry("0.txt", false))).size());
        assertEquals(2, new SmbBrowserRows(Arrays.asList(entry("a.mp3", false),
                entry("b.mkv", false))).size());
        SmbBrowserRows rows = new SmbBrowserRows(Arrays.asList(entry("folder", true),
                entry("z.txt", false), entry("A.txt", false), entry("a.txt", false)));
        assertEquals(5, rows.size());
        assertNull(rows.get(1));
        assertEquals("A.txt", rows.get(2).name);
        assertEquals("a.txt", rows.get(3).name);
        assertEquals("z.txt", rows.get(4).name);
    }

    @Test
    public void naturallySortsEveryGroupWithoutChangingSeparatorsOrAnchors() {
        SmbEntry directory = entry("Folder 2", true);
        SmbEntry video = entry("Clip 2.mp4", false);
        SmbEntry other = entry("Notes 2.txt", false);
        List<SmbEntry> entries = Arrays.asList(entry("Notes 10.txt", false),
                entry("Clip 10.mp3", false), entry("Folder 10", true), other, video, directory);
        SmbBrowserRows rows = new SmbBrowserRows(entries);
        assertEquals(7, rows.size());
        assertEquals(directory, rows.get(0));
        assertEquals("Folder 10", rows.get(1).name);
        assertEquals(video, rows.get(2));
        assertEquals("Clip 10.mp3", rows.get(3).name);
        assertNull(rows.get(4));
        assertEquals(other, rows.get(5));
        assertEquals("Notes 10.txt", rows.get(6).name);
        assertEquals(2, rows.indexOf(video.location.getUrl()));
        assertEquals("Notes 10.txt", entries.get(0).name);
    }

    @Test
    public void comparesEveryNumericRunRatherThanOnlyTheFirst() {
        assertNameOrder("Episode 1", "Episode 2", "Episode 10", "Episode 20");
        assertNameOrder("S2E2.mkv", "S2E10.mkv", "S10E1.mkv", "S10E2.mkv");
        assertNameOrder("2", "2a", "2a2", "2a10", "10");
    }

    @Test
    public void comparesNumbersLargerThanLongWithoutOverflow() {
        assertNameOrder("Clip 9", "Clip 9223372036854775807", "Clip 9223372036854775808",
                "Clip 10000000000000000000", "Clip 9999999999999999999999999999999999999999",
                "Clip 10000000000000000000000000000000000000000");
    }

    @Test
    public void ignoresLeadingZerosUntilNaturalNamesAreOtherwiseEqual() {
        assertNameOrder("0", "00", "000", "1", "01", "001", "2", "02", "002", "10");
        assertNameOrder("Clip 02a", "Clip 2b", "Clip 002c", "Clip 10a");
        assertNameOrder("S02E2", "S2E10", "S02E10");
    }

    @Test
    public void comparesTextCaseInsensitivelyBeforeApplyingDeterministicTies() {
        assertNameOrder("Alpha", "alpha", "beta");
        assertNameOrder("CLIP2.mp4", "clip2.mp4", "Clip10.mp4");
        assertNameOrder("Café 2", "café 10");
        assertNameOrder("Clip #2 + 100%", "clip #10 + 2%", "日本2", "日本10");
    }

    @Test
    public void comparatorRemainsAntisymmetricAndTransitiveWithMixedNames() {
        List<SmbEntry> entries = new ArrayList<>();
        for (String name : Arrays.asList("0", "00", "2", "02", "10", "A", "a", "A2", "a02",
                "A02a", "a2b", "A10", "a2-2", "A02-10", "café2", "CAFÉ10", "日本2")) {
            entries.add(entry(name, true));
            entries.add(entry(name + ".mp4", false));
            entries.add(entry(name + ".txt", false));
        }
        for (SmbEntry first : entries) {
            assertEquals(0, SmbEntry.BROWSER_ORDER.compare(first, first));
            for (SmbEntry second : entries) {
                int order = SmbEntry.BROWSER_ORDER.compare(first, second);
                assertEquals(Integer.signum(order),
                        -Integer.signum(SmbEntry.BROWSER_ORDER.compare(second, first)));
                for (SmbEntry third : entries) {
                    if (order <= 0 && SmbEntry.BROWSER_ORDER.compare(second, third) <= 0) {
                        assertTrue(SmbEntry.BROWSER_ORDER.compare(first, third) <= 0);
                    }
                }
            }
        }
    }

    private static void assertNameOrder(String... names) {
        List<SmbEntry> entries = Arrays.stream(names).map(name -> entry(name, true))
                .collect(Collectors.toList());
        Collections.shuffle(entries, new Random(42));
        entries.sort(SmbEntry.BROWSER_ORDER);
        assertEquals(Arrays.asList(names), entries.stream().map(entry -> entry.name)
                .collect(Collectors.toList()));
    }

    private static SmbEntry entry(String name, boolean directory) {
        return new SmbEntry(name, directory, SmbLocation.parse("nas/share").child(name, directory), 12);
    }
}