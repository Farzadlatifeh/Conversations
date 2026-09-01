package eu.siacs.conversations.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Message;
import org.junit.Test;

public class MessageParserStickerTest {

    private static final String CID =
            "sha1+a9993e364706816aba3e25717850c26c9cd0d89d@bob.xmpp.org";

    @Test
    public void recognizesMovimImportedXhtmlTree() {
        final Message packet = new Message();
        final Element html = packet.addChild("html", Namespace.XHTML_IM);
        final Element body = html.addChild("body", Namespace.XHTML);
        final Element paragraph = body.addChild("p");
        paragraph
                .addChild("img")
                .setAttribute("src", "cid:" + CID)
                .setAttribute("alt", "Sticker");

        assertEquals(CID, MessageParser.getXhtmlStickerCid(packet));
    }

    @Test
    public void recognizesAdditionalMovimWrapper() {
        final Message packet = new Message();
        final Element body =
                packet.addChild("html", Namespace.XHTML_IM)
                        .addChild("body", Namespace.XHTML);
        body.addChild("div")
                .addChild("p")
                .addChild("img")
                .setAttribute("src", "CID:" + CID)
                .setAttribute("alt", "Sticker");

        assertEquals(CID, MessageParser.getXhtmlStickerCid(packet));
    }

    @Test
    public void rejectsNonStickerInlineImage() {
        final Message packet = new Message();
        packet.addChild("html", Namespace.XHTML_IM)
                .addChild("body", Namespace.XHTML)
                .addChild("img")
                .setAttribute("src", "cid:" + CID)
                .setAttribute("alt", ":custom-emoji:");

        assertNull(MessageParser.getXhtmlStickerCid(packet));
    }
}
