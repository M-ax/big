package com.example.big.smb;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class SmbScrollPositionTest {
    @Test
    public void followsAnchorAcrossInsertedRowsAndFallsBackWhenDeleted() {
        SmbLocation folder = SmbLocation.parse("nas/share");
        SmbEntry movie = new SmbEntry("movie.mp4", false, folder.child("movie.mp4", false), 1);
        SmbEntry added = new SmbEntry("a.mp3", false, folder.child("a.mp3", false), 1);
        SmbScrollPosition position = new SmbScrollPosition(1, -17, movie.location.getUrl(), false);
        assertEquals(2, position.resolve(new SmbBrowserRows(Arrays.asList(movie, added)), 1));
        assertEquals(-17, position.top);
        assertEquals(1, position.resolve(new SmbBrowserRows(Collections.singletonList(added)), 1));
        assertEquals(0, position.resolve(new SmbBrowserRows(Collections.emptyList()), 1));
    }

    @Test
    public void separatorAndHeaderAnchorsHaveIndependentCoordinates() {
        SmbLocation folder = SmbLocation.parse("nas/share");
        SmbEntry movie = new SmbEntry("movie.mp4", false, folder.child("movie.mp4", false), 1);
        SmbEntry text = new SmbEntry("a.txt", false, folder.child("a.txt", false), 1);
        SmbBrowserRows rows = new SmbBrowserRows(Arrays.asList(text, movie));
        SmbScrollPosition separator = new SmbScrollPosition(2, -3, rows.anchorAt(1), false);
        assertEquals(2, separator.resolve(rows, 1));
        assertEquals(0, new SmbScrollPosition(0, -28, null, true).resolve(rows, 1));
        assertEquals(0, new SmbScrollPosition(-9, 0, null, true).position);
    }
}