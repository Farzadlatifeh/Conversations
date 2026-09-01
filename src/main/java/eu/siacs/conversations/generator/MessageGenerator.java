package eu.siacs.conversations.generator;

import android.util.Log;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlMessage;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.correction.Replace;
import im.conversations.android.xmpp.model.hints.Store;
import im.conversations.android.xmpp.model.markers.Markable;
import im.conversations.android.xmpp.model.unique.OriginId;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MessageGenerator extends AbstractGenerator {
    private static final String MOVIM_STICKER_FALLBACK =
            "A sticker has been sent using Movim";
    private static final String OMEMO_FALLBACK_MESSAGE =
            "I sent you an OMEMO encrypted message but your client doesn’t seem to support that."
                    + " Find more information on https://conversations.im/omemo";
    private static final String PGP_FALLBACK_MESSAGE =
            "I sent you a PGP encrypted message but your client doesn’t seem to support that.";

    public MessageGenerator(XmppConnectionService service) {
        super(service);
    }

    private im.conversations.android.xmpp.model.stanza.Message preparePacket(
            final Message message) {
        Conversation conversation = (Conversation) message.getConversation();
        Account account = conversation.getAccount();
        im.conversations.android.xmpp.model.stanza.Message packet =
                new im.conversations.android.xmpp.model.stanza.Message();
        final boolean isWithSelf = conversation.getContact().isSelf();
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            packet.setTo(message.getCounterpart());
            packet.setType(im.conversations.android.xmpp.model.stanza.Message.Type.CHAT);
            if (!isWithSelf) {
                packet.addChild("request", "urn:xmpp:receipts");
            }
        } else if (message.isPrivateMessage()) {
            packet.setTo(message.getCounterpart());
            packet.setType(im.conversations.android.xmpp.model.stanza.Message.Type.CHAT);
            packet.addChild("x", "http://jabber.org/protocol/muc#user");
            packet.addChild("request", "urn:xmpp:receipts");
        } else {
            packet.setTo(message.getCounterpart().asBareJid());
            packet.setType(im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT);
        }
        if (conversation.isSingleOrPrivateAndNonAnonymous() && !message.isPrivateMessage()) {
            packet.addExtension(new Markable());
        }
        packet.setFrom(account.getJid());
        packet.setId(message.getUuid());
        if (conversation.getMode() == Conversational.MODE_MULTI
                && !message.isPrivateMessage()
                && !conversation.getMucOptions().stableId()) {
            packet.addExtension(new OriginId(message.getUuid()));
        }
        if (message.edited()) {
            packet.addExtension(new Replace(message.getEditedIdWireFormat()));
        }
        return packet;
    }

    public im.conversations.android.xmpp.model.stanza.Message generateAxolotlChat(
            Message message, XmppAxolotlMessage axolotlMessage) {
        im.conversations.android.xmpp.model.stanza.Message packet = preparePacket(message);
        if (axolotlMessage == null) {
            return null;
        }
        packet.setAxolotlMessage(axolotlMessage.toElement());
        packet.setBody(OMEMO_FALLBACK_MESSAGE);
        packet.addExtension(new Store());
        packet.addChild("encryption", "urn:xmpp:eme:0")
                .setAttribute("name", "OMEMO")
                .setAttribute("namespace", AxolotlService.PEP_PREFIX);
        addStickerMarker(packet, message);
        return packet;
    }

    public im.conversations.android.xmpp.model.stanza.Message generateKeyTransportMessage(
            Jid to, XmppAxolotlMessage axolotlMessage) {
        im.conversations.android.xmpp.model.stanza.Message packet =
                new im.conversations.android.xmpp.model.stanza.Message();
        packet.setType(im.conversations.android.xmpp.model.stanza.Message.Type.CHAT);
        packet.setTo(to);
        packet.setAxolotlMessage(axolotlMessage.toElement());
        packet.addChild(new Store());
        return packet;
    }

    public im.conversations.android.xmpp.model.stanza.Message generateChat(Message message) {
        im.conversations.android.xmpp.model.stanza.Message packet = preparePacket(message);
        String content;
        if (message.hasFileOnRemoteHost()) {
            final Message.FileParams fileParams = message.getFileParams();
            final boolean movimSticker = message.isSticker() && prefersMovimSticker(message);
            if (movimSticker) {
                content = MOVIM_STICKER_FALLBACK;
                addMovimStickerCompatibility(packet, message);
            } else {
                content = fileParams.url;
                packet.addChild("x", Namespace.OOB).addChild("url").setContent(content);
                addStickerPayload(packet, message);
                if (message.isSticker()) {
                    content = message.getStickerFallback();
                    addMovimStickerCompatibility(packet, message);
                }
            }
        } else {
            content = message.getBody();
        }
        packet.setBody(content);
        return packet;
    }

    private static boolean prefersMovimSticker(final Message message) {
        if (message.getConversation().getMode() != Conversation.MODE_SINGLE) {
            return false;
        }
        final var contact = message.getContact();
        if (contact == null) {
            return false;
        }
        for (final var capability : contact.getCapabilities()) {
            if (!capability.isPresent()) {
                continue;
            }
            final var info = capability.get();
            boolean movimIdentity = false;
            for (final var identity : info.getIdentities()) {
                final String name = identity.getIdentityName();
                if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains("movim")) {
                    movimIdentity = true;
                    break;
                }
            }
            if (movimIdentity
                    || (!info.hasFeature(Namespace.STICKERS)
                            && info.hasFeature(Namespace.BOB)
                            && info.hasFeature(Namespace.XHTML_IM))) {
                return true;
            }
        }
        return false;
    }

    public im.conversations.android.xmpp.model.stanza.Message generatePgpChat(Message message) {
        final im.conversations.android.xmpp.model.stanza.Message packet = preparePacket(message);
        if (message.hasFileOnRemoteHost()) {
            Message.FileParams fileParams = message.getFileParams();
            final String url = fileParams.url;
            packet.setBody(url);
            packet.addChild("x", Namespace.OOB).addChild("url").setContent(url);
            addStickerPayload(packet, message);
        } else {
            packet.setBody(PGP_FALLBACK_MESSAGE);
            if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                packet.addChild("x", "jabber:x:encrypted").setContent(message.getEncryptedBody());
            } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
                packet.addChild("x", "jabber:x:encrypted").setContent(message.getBody());
            }
            packet.addChild("encryption", "urn:xmpp:eme:0")
                    .setAttribute("namespace", "jabber:x:encrypted");
        }
        return packet;
    }

    private static void addStickerMarker(
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final Message message) {
        if (message.isSticker()) {
            final Element sticker = packet.addChild("sticker", Namespace.STICKERS);
            if (message.getStickerPackId() != null) {
                sticker.setAttribute("pack", message.getStickerPackId());
            }
        }
    }

    private void addStickerPayload(
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final Message message) {
        if (!message.isSticker()) {
            return;
        }
        addStickerMarker(packet, message);
        final Message.FileParams params = message.getFileParams();
        if (params.url == null) {
            return;
        }
        final Element sharing =
                packet.addChild("file-sharing", Namespace.STATELESS_FILE_SHARING)
                        .setAttribute("disposition", "inline");
        final Element file = sharing.addChild("file", Namespace.FILE_METADATA);
        final String mimeType = message.getMimeType();
        if (mimeType != null) {
            file.addChild("media-type").setContent(mimeType);
        }
        if (params.size != null) {
            file.addChild("size").setContent(Long.toString(params.size));
        }
        if (params.width > 0 && params.height > 0) {
            file.addChild("dimensions").setContent(params.width + "x" + params.height);
        }
        file.addChild("desc").setContent(message.getStickerFallback());
        try {
            final byte[] bytes =
                    Files.toByteArray(mXmppConnectionService.getFileBackend().getFile(message));
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            file.addChild("hash", Namespace.HASHES)
                    .setAttribute("algo", "sha-256")
                    .setContent(BaseEncoding.base64().encode(digest));
        } catch (final IOException | NoSuchAlgorithmException e) {
            Log.w(Config.LOGTAG, "Could not calculate sticker hash", e);
        }
        sharing.addChild("sources")
                .addChild("url-data", Namespace.URL_DATA)
                .setAttribute("target", params.url);
        packet.addChild("fallback", Namespace.FALLBACK_INDICATION)
                .setAttribute("for", Namespace.STATELESS_FILE_SHARING)
                .addChild("body");
    }

    private void addMovimStickerCompatibility(
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final Message message) {
        if (message.getEncryption() != Message.ENCRYPTION_NONE) {
            return;
        }
        try {
            final String cid = mXmppConnectionService.registerBobSticker(message);
            final Element html = packet.addChild("html", Namespace.XHTML_IM);
            final Element paragraph = html.addChild("body", Namespace.XHTML).addChild("p");
            paragraph
                    .addChild("img")
                    .setAttribute("src", "cid:" + cid)
                    .setAttribute("alt", "Sticker");
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "Could not register Movim-compatible sticker", e);
        }
    }
}
