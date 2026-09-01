package eu.siacs.conversations.xmpp.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class BobManagerTest {

    @Test
    public void createsAndParsesSha1Cid() {
        final String cid = BobManager.cid("sha1", "abc".getBytes(StandardCharsets.UTF_8));

        assertEquals("sha1+a9993e364706816aba3e25717850c26c9cd0d89d@bob.xmpp.org", cid);
        assertEquals("sha1", BobManager.parseCidAlgorithm(cid));
    }

    @Test
    public void createsAndParsesSha256Cid() {
        final String cid = BobManager.cid("SHA-256", "abc".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                "sha256+ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad@bob.xmpp.org",
                cid);
        assertEquals("sha256", BobManager.parseCidAlgorithm(cid));
        assertEquals(
                "sha256",
                BobManager.parseCidAlgorithm(
                        "sha-256+ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad@bob.xmpp.org"));
    }

    @Test
    public void rejectsMalformedCid() {
        assertNull(BobManager.parseCidAlgorithm("sha1+1234@bob.xmpp.org"));
        assertNull(
                BobManager.parseCidAlgorithm("md5+900150983cd24fb0d6963f7d28e17f72@bob.xmpp.org"));
        assertNull(
                BobManager.parseCidAlgorithm(
                        "sha1+A9993E364706816ABA3E25717850C26C9CD0D89D@bob.xmpp.org"));
        assertNull(BobManager.parseCidAlgorithm("not-a-cid"));
    }
}
