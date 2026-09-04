package eu.siacs.conversations.xmpp.manager;

import android.graphics.BitmapFactory;
import android.util.Log;
import com.google.common.base.Strings;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.UnsignedBytes;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import de.gultsch.common.MiniUri;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.stickers.StickerPack;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.NodeConfiguration;
import im.conversations.android.xmpp.model.stickers.Pack;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.Request;

/** Fetches, validates, stores, and republishes XEP-0449 sticker packs. */
public final class StickerPackManager extends AbstractManager {

    private static final int MAX_ITEMS = 100;
    private static final int MAX_ITEM_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PACK_BYTES = 32 * 1024 * 1024;
    private final XmppConnectionService service;

    public StickerPackManager(
            final XmppConnectionService service, final XmppConnection connection) {
        super(service, connection);
        this.service = service;
    }

    public ListenableFuture<StickerPack.Pack> importPack(final String uriString) {
        final MiniUri.Xmpp uri = MiniUri.getXmppUriOrNull(uriString);
        if (uri == null
                || !uri.isAddress()
                || !uri.isAction("pubsub")
                || !"retrieve".equals(uri.getParameter("action"))) {
            return Futures.immediateFailedFuture(
                    new IllegalArgumentException("Not an XEP-0449 sticker-pack URI"));
        }
        final Jid source = uri.asJid().asBareJid();
        final String node = uri.getParameter("node");
        final String itemId = uri.getParameter("item");
        if (Strings.isNullOrEmpty(node) || Strings.isNullOrEmpty(itemId)) {
            return Futures.immediateFailedFuture(
                    new IllegalArgumentException("Sticker-pack URI is missing node or item"));
        }
        final var fetch =
                getManager(PubSubManager.class).fetchItem(source, node, itemId, Pack.class);
        return Futures.transformAsync(
                fetch,
                pack -> {
                    if (pack.isRestricted()) {
                        return Futures.immediateFailedFuture(
                                new IllegalArgumentException("This sticker pack is restricted"));
                    }
                    final ListenableFuture<StickerPack.Pack> local =
                            Futures.submit(
                                    () -> downloadAndInstall(itemId, pack),
                                    XmppConnectionService.FILE_ATTACHMENT_EXECUTOR);
                    return Futures.transformAsync(
                            local,
                            installed -> {
                                final Jid ownJid = getAccount().getJid().asBareJid();
                                if (source.equals(ownJid)) {
                                    return Futures.immediateFuture(installed);
                                }
                                final var publish =
                                        getManager(PubSubManager.class)
                                                .publish(
                                                        ownJid,
                                                        pack,
                                                        itemId,
                                                        Namespace.STICKERS,
                                                        NodeConfiguration.OPEN_MAX_ITEMS);
                                final var bestEffortPublish =
                                        Futures.catching(
                                                publish,
                                                Exception.class,
                                                error -> {
                                                    Log.w(
                                                            Config.LOGTAG,
                                                            "Could not republish imported sticker"
                                                                    + " pack",
                                                            error);
                                                    return null;
                                                },
                                                MoreExecutors.directExecutor());
                                return Futures.transform(
                                        bestEffortPublish,
                                        ignored -> installed,
                                        MoreExecutors.directExecutor());
                            },
                            MoreExecutors.directExecutor());
                },
                MoreExecutors.directExecutor());
    }

    private StickerPack.Pack downloadAndInstall(final String itemId, final Pack pack)
            throws IOException {
        verifyPackHash(itemId, pack);
        final List<Element> sourceItems = pack.getItems();
        if (sourceItems.isEmpty() || sourceItems.size() > MAX_ITEMS) {
            throw new IOException("Sticker pack contains an invalid number of items");
        }
        final List<StickerPack.InstallItem> installed = new ArrayList<>();
        int totalBytes = 0;
        for (final Element sourceItem : sourceItems) {
            final ParsedItem item = parseItem(sourceItem);
            final byte[] bytes = download(item.url());
            totalBytes += bytes.length;
            if (totalBytes > MAX_PACK_BYTES) {
                throw new IOException("Sticker pack exceeds the size limit");
            }
            verifyImage(item, bytes);
            installed.add(
                    new StickerPack.InstallItem(
                            bytes, item.mimeType(), item.fallback(), item.name()));
        }
        return StickerPack.install(
                service,
                itemId,
                Strings.isNullOrEmpty(pack.getDisplayName())
                        ? service.getString(eu.siacs.conversations.R.string.unnamed_sticker_pack)
                        : pack.getDisplayName(),
                pack.getSummary(),
                installed);
    }

    private ParsedItem parseItem(final Element sourceItem) throws IOException {
        final Element file = sourceItem.findChild("file", Namespace.FILE_METADATA);
        final Element sources = sourceItem.findChild("sources", Namespace.STATELESS_FILE_SHARING);
        final Element urlData =
                sources == null ? null : sources.findChild("url-data", Namespace.URL_DATA);
        final String mimeType = file == null ? null : file.findChildContent("media-type");
        final String fallback = file == null ? null : file.findChildContent("desc");
        final String target = urlData == null ? null : urlData.getAttribute("target");
        final HttpUrl url = target == null ? null : HttpUrl.parse(target);
        final Element hash = findSha256(file);
        if (file == null
                || hash == null
                || Strings.isNullOrEmpty(hash.getContent())
                || mimeType == null
                || !mimeType.startsWith("image/")
                || Strings.isNullOrEmpty(fallback)
                || url == null
                || !"https".equals(url.scheme())) {
            throw new IOException("Sticker pack contains invalid item metadata");
        }
        final Element suggestionElement = sourceItem.findChild("suggest", Namespace.STICKERS);
        final String suggestion = suggestionElement == null ? null : suggestionElement.getContent();
        final String name = Strings.isNullOrEmpty(suggestion) ? fallback : suggestion;
        return new ParsedItem(url, mimeType, fallback, name, hash.getContent());
    }

    private byte[] download(final HttpUrl url) throws IOException {
        final var client =
                service.getHttpConnectionManager().buildHttpClient(url, getAccount(), 30, false);
        try (final var response =
                client.newCall(new Request.Builder().url(url).get().build()).execute()) {
            final var body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("Sticker download failed with HTTP " + response.code());
            }
            final long declaredLength = body.contentLength();
            if (declaredLength > MAX_ITEM_BYTES) {
                throw new IOException("Sticker exceeds the size limit");
            }
            final byte[] bytes =
                    ByteStreams.toByteArray(
                            ByteStreams.limit(body.byteStream(), MAX_ITEM_BYTES + 1L));
            if (bytes.length == 0 || bytes.length > MAX_ITEM_BYTES) {
                throw new IOException("Sticker exceeds the size limit");
            }
            return bytes;
        }
    }

    private static void verifyImage(final ParsedItem item, final byte[] bytes) throws IOException {
        final String actual = BaseEncoding.base64().encode(messageDigest("SHA-256").digest(bytes));
        if (!actual.equals(item.sha256())) {
            throw new IOException("Sticker hash verification failed");
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("Sticker is not a decodable image");
        }
    }

    private static void verifyPackHash(final String itemId, final Pack pack) throws IOException {
        final Element declared = findSha256(pack);
        if (declared == null || Strings.isNullOrEmpty(declared.getContent())) {
            throw new IOException("Sticker pack has no SHA-256 hash");
        }
        final List<byte[]> metadata = new ArrayList<>();
        final List<byte[]> stickers = new ArrayList<>();
        for (final Element child : pack.getChildren()) {
            if (("name".equals(child.getName()) || "summary".equals(child.getName()))
                    && Namespace.STICKERS.equals(child.getNamespace())) {
                final ByteArrayOutputStream value = new ByteArrayOutputStream();
                write(value, child.getName());
                value.write(0x1f);
                write(value, Strings.nullToEmpty(child.getAttribute("xml:lang")));
                value.write(0x1f);
                write(value, Strings.nullToEmpty(child.getContent()));
                value.write(0x1f);
                value.write(0x1e);
                metadata.add(value.toByteArray());
            } else if ("item".equals(child.getName())
                    && Namespace.STICKERS.equals(child.getNamespace())) {
                final Element file = child.findChild("file", Namespace.FILE_METADATA);
                if (file == null) {
                    throw new IOException("Sticker pack item has no file metadata");
                }
                final String description = file.findChildContent("desc");
                final List<byte[]> hashes = new ArrayList<>();
                for (final Element field : file.getChildren()) {
                    if ("hash".equals(field.getName())
                            && Namespace.HASHES.equals(field.getNamespace())) {
                        final ByteArrayOutputStream hashValue = new ByteArrayOutputStream();
                        write(hashValue, Strings.nullToEmpty(field.getAttribute("algo")));
                        hashValue.write(0x1f);
                        write(hashValue, Strings.nullToEmpty(field.getContent()));
                        hashValue.write(0x1f);
                        hashValue.write(0x1e);
                        hashes.add(hashValue.toByteArray());
                    }
                }
                if (Strings.isNullOrEmpty(description) || hashes.isEmpty()) {
                    throw new IOException("Sticker pack item is missing description or hash");
                }
                hashes.sort(UnsignedBytes.lexicographicalComparator());
                final ByteArrayOutputStream value = new ByteArrayOutputStream();
                write(value, description);
                value.write(0x1e);
                for (final byte[] hash : hashes) {
                    value.write(hash, 0, hash.length);
                }
                value.write(0x1d);
                stickers.add(value.toByteArray());
            }
        }
        final Comparator<byte[]> comparator = UnsignedBytes.lexicographicalComparator();
        metadata.sort(comparator);
        stickers.sort(comparator);
        final ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        for (final byte[] value : metadata) {
            canonical.write(value, 0, value.length);
        }
        canonical.write(0x1c);
        for (final byte[] value : stickers) {
            canonical.write(value, 0, value.length);
        }
        canonical.write(0x1c);
        final String calculated =
                BaseEncoding.base64()
                        .encode(messageDigest("SHA-256").digest(canonical.toByteArray()));
        if (!calculated.equals(declared.getContent())
                || calculated.length() < 24
                || !calculated.substring(0, 24).equals(itemId)) {
            throw new IOException("Sticker-pack hash verification failed");
        }
    }

    private static Element findSha256(final Element parent) {
        if (parent == null) {
            return null;
        }
        for (final Element child : parent.getChildren()) {
            if ("hash".equals(child.getName())
                    && Namespace.HASHES.equals(child.getNamespace())
                    && "sha-256".equals(child.getAttribute("algo"))) {
                return child;
            }
        }
        return null;
    }

    private static void write(final ByteArrayOutputStream output, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(bytes, 0, bytes.length);
    }

    private static MessageDigest messageDigest(final String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record ParsedItem(
            HttpUrl url, String mimeType, String fallback, String name, String sha256) {}
}
