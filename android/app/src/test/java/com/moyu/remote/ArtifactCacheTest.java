package com.moyu.remote;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public final class ArtifactCacheTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void storesValidatedImageUnderPrivateCacheRoot() throws Exception {
        ArtifactCache cache = new ArtifactCache(temporary.getRoot());
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        String id = "11111111-1111-4111-8111-111111111111";
        File stored = cache.store(id, png, "image/png", null);
        assertNotNull(cache.find(id));
        assertEquals("image/png", cache.mime(id));
        byte[] actual = new byte[png.length];
        try (java.io.InputStream input = cache.open(id)) { assertEquals(png.length, input.read(actual)); }
        assertArrayEquals(png, actual);
        assertEquals(stored.getCanonicalFile(), cache.find(id).getCanonicalFile());
    }

    @Test(expected = IOException.class) public void rejectsMimeMagicMismatch() throws Exception {
        ArtifactCache cache = new ArtifactCache(temporary.getRoot());
        cache.store("22222222-2222-4222-8222-222222222222", new byte[]{1, 2, 3, 4}, "image/png", null);
    }

    @Test public void rejectsTraversalAndLooseIdentifiers() {
        assertFalse(ArtifactCache.isValidId("------------------------------------"));
        assertFalse(ArtifactCache.isValidId("../11111111-1111-4111-8111-111111111111"));
    }
}
