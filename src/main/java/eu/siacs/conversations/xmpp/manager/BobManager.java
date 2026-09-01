package eu.siacs.conversations.xmpp.manager;

import android.content.Context;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.bob.Data;
import im.conversations.android.xmpp.model.error.Condition;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** XEP-0231 Bits of Binary transport used by Movim for stickers. */
public final class BobManager extends AbstractManager {

    private static final int MAX_CACHE_ENTRIES = 32;
    private static final int MAX_STICKER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CACHE_BYTES = 32 * 1024 * 1024;
    private static final Pattern CID_PATTERN =
            Pattern.compile("^(sha1|sha256)\\+([0-9a-f]+)@bob\\.xmpp\\.org$");
    private final Map<String, Content> cache = new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true);
    private int cacheBytes;

    public BobManager(final Context context, final XmppConnection connection) {
        super(context, connection);
    }

    public String register(final File file, final String mimeType) throws IOException {
        final byte[] bytes = Files.toByteArray(file);
        if (mimeType == null
                || !mimeType.startsWith("image/")
                || bytes.length == 0
                || bytes.length > MAX_STICKER_BYTES) {
            throw new IOException("Sticker is empty or too large for Bits of Binary");
        }
        final String cid = cid("sha1", bytes);
        synchronized (cache) {
            final Content previous = cache.put(cid, new Content(cid, mimeType, bytes));
            if (previous != null) {
                cacheBytes -= previous.bytes().length;
            }
            cacheBytes += bytes.length;
            trimCache();
        }
        return cid;
    }

    public void handleRequest(final Iq request) {
        if (request.getType() != Iq.Type.GET) {
            connection.sendErrorFor(request, new Condition.BadRequest());
            return;
        }
        final Data requested = request.getExtension(Data.class);
        final String cid = requested == null ? null : requested.getCid();
        final Content content;
        synchronized (cache) {
            content = cid == null ? null : cache.get(cid);
        }
        if (content == null) {
            connection.sendErrorFor(request, new Condition.ItemNotFound());
            return;
        }
        final Iq response = request.generateResponse(Iq.Type.RESULT);
        final Data data = response.addExtension(new Data());
        data.setCid(content.cid());
        data.setType(content.mimeType());
        data.setMaxAge(86400);
        data.setContent(content.bytes());
        connection.sendIqPacket(response, null, false);
    }

    public ListenableFuture<Content> fetch(final Jid from, final String cid) {
        final String algorithm = parseCidAlgorithm(cid);
        if (from == null || algorithm == null) {
            return Futures.immediateFailedFuture(new IllegalArgumentException("Invalid BoB CID"));
        }
        final Iq request = new Iq(Iq.Type.GET);
        request.setTo(from);
        final Data query = request.addExtension(new Data());
        query.setCid(cid);
        return Futures.transform(
                connection.sendIqPacket(request),
                response -> {
                    if (response.getType() != Iq.Type.RESULT) {
                        throw new IllegalStateException("BoB request failed");
                    }
                    final Data data = response.getExtension(Data.class);
                    if (data == null || !cid.equals(data.getCid())) {
                        throw new IllegalStateException("BoB response has the wrong CID");
                    }
                    final String mimeType = data.getType();
                    final byte[] bytes = data.asBytes();
                    if (mimeType == null
                            || !mimeType.startsWith("image/")
                            || bytes.length == 0
                            || bytes.length > MAX_STICKER_BYTES
                            || !cid.equals(cid(algorithm, bytes))) {
                        throw new IllegalStateException("Invalid BoB sticker payload");
                    }
                    return new Content(cid, mimeType, bytes);
                },
                Runnable::run);
    }

    private void trimCache() {
        final var iterator = cache.entrySet().iterator();
        while ((cache.size() > MAX_CACHE_ENTRIES || cacheBytes > MAX_CACHE_BYTES)
                && iterator.hasNext()) {
            final Content removed = iterator.next().getValue();
            cacheBytes -= removed.bytes().length;
            iterator.remove();
        }
    }

    public static String parseCidAlgorithm(final String cid) {
        if (cid == null) {
            return null;
        }
        final Matcher matcher = CID_PATTERN.matcher(cid);
        if (!matcher.matches()) {
            return null;
        }
        final String algorithm = matcher.group(1);
        final int expectedLength = "sha1".equals(algorithm) ? 40 : 64;
        return matcher.group(2).length() == expectedLength ? algorithm : null;
    }

    static String cid(final String algorithm, final byte[] bytes) {
        final String normalized = algorithm.toLowerCase(Locale.ROOT).replace("-", "");
        final String javaName =
                switch (normalized) {
                    case "sha1" -> "SHA-1";
                    case "sha256" -> "SHA-256";
                    default -> throw new IllegalArgumentException("Unsupported BoB hash algorithm");
                };
        try {
            final byte[] digest = MessageDigest.getInstance(javaName).digest(bytes);
            return normalized
                    + '+'
                    + BaseEncoding.base16().lowerCase().encode(digest)
                    + "@bob.xmpp.org";
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Content(String cid, String mimeType, byte[] bytes) {}
}
