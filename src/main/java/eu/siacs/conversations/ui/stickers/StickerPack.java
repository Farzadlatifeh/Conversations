package eu.siacs.conversations.ui.stickers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import androidx.annotation.DrawableRes;
import androidx.appcompat.content.res.AppCompatResources;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import eu.siacs.conversations.R;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.utils.MimeUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local sticker-pack storage plus an original starter pack bundled with this fork. */
public final class StickerPack {

    private static final String MANIFEST = "manifest.json";
    private static final int MAX_PACKS = 50;
    private static final int MAX_ITEMS = 100;

    public static final String STARTER_ID = "open-starter-v1";

    private StickerPack() {}

    public static List<Pack> load(final Context context) {
        final List<Pack> packs = new ArrayList<>();
        packs.add(
                new Pack(
                        STARTER_ID,
                        context.getString(R.string.starter_sticker_pack),
                        context.getString(R.string.starter_sticker_pack_summary),
                        List.of(
                                Item.builtIn(R.drawable.sticker_happy, "😊", "Happy"),
                                Item.builtIn(R.drawable.sticker_love, "❤️", "Love"),
                                Item.builtIn(R.drawable.sticker_party, "🥳", "Party"),
                                Item.builtIn(R.drawable.sticker_wow, "😮", "Wow"),
                                Item.builtIn(R.drawable.sticker_sad, "😢", "Sad"),
                                Item.builtIn(R.drawable.sticker_thumb, "👍", "Thumbs up"),
                                Item.builtInEmoji("😂", "😂", "Laughing"),
                                Item.builtInEmoji("🤣", "🤣", "Rolling laughing"),
                                Item.builtInEmoji("😉", "😉", "Wink"),
                                Item.builtInEmoji("😍", "😍", "Heart eyes"),
                                Item.builtInEmoji("😘", "😘", "Kiss"),
                                Item.builtInEmoji("😎", "😎", "Cool"),
                                Item.builtInEmoji("🤔", "🤔", "Thinking"),
                                Item.builtInEmoji("😴", "😴", "Sleeping"),
                                Item.builtInEmoji("🤢", "🤢", "Sick"),
                                Item.builtInEmoji("😡", "😡", "Angry"),
                                Item.builtInEmoji("🤯", "🤯", "Mind blown"),
                                Item.builtInEmoji("👋", "👋", "Hello"),
                                Item.builtInEmoji("👏", "👏", "Clapping"),
                                Item.builtInEmoji("🙏", "🙏", "Please"),
                                Item.builtInEmoji("👌", "👌", "OK"),
                                Item.builtInEmoji("🔥", "🔥", "Fire"),
                                Item.builtInEmoji("⭐", "⭐", "Star"),
                                Item.builtInEmoji("🎂", "🎂", "Birthday"))));
        final File root = root(context);
        final File[] directories = root.listFiles(File::isDirectory);
        if (directories == null) {
            return packs;
        }
        final List<Pack> imported = new ArrayList<>();
        for (final File directory : directories) {
            if (directory.getName().startsWith(".import-")
                    || directory.getName().startsWith(".backup-")) {
                continue;
            }
            try {
                imported.add(read(directory));
            } catch (final IOException | JSONException ignored) {
                // Ignore malformed or incomplete packs without breaking the picker.
            }
            if (imported.size() >= MAX_PACKS) {
                break;
            }
        }
        imported.sort(Comparator.comparing(Pack::name, String.CASE_INSENSITIVE_ORDER));
        packs.addAll(imported);
        return packs;
    }

    public static Pack install(
            final Context context,
            final String id,
            final String name,
            final String summary,
            final List<InstallItem> items)
            throws IOException {
        if (!validId(id) || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IOException("Invalid sticker pack");
        }
        final File root = root(context);
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Could not create sticker-pack directory");
        }
        final File staging = new File(root, ".import-" + UUID.randomUUID());
        if (!staging.mkdir()) {
            throw new IOException("Could not create sticker-pack staging directory");
        }
        try {
            final JSONArray manifestItems = new JSONArray();
            for (int i = 0; i < items.size(); ++i) {
                final InstallItem item = items.get(i);
                final String extension = safeExtension(item.mimeType());
                final String filename = String.format(Locale.ROOT, "sticker-%03d.%s", i, extension);
                Files.write(item.bytes(), new File(staging, filename));
                manifestItems.put(
                        new JSONObject()
                                .put("file", filename)
                                .put("mime", item.mimeType())
                                .put("fallback", item.fallback())
                                .put("name", item.name()));
            }
            final JSONObject manifest =
                    new JSONObject()
                            .put("version", 1)
                            .put("id", id)
                            .put(
                                    "name",
                                    Strings.isNullOrEmpty(name)
                                            ? context.getString(R.string.unnamed_sticker_pack)
                                            : name)
                            .put("summary", Strings.nullToEmpty(summary))
                            .put("items", manifestItems);
            Files.asCharSink(new File(staging, MANIFEST), StandardCharsets.UTF_8)
                    .write(manifest.toString());
            final File destination = new File(root, directoryName(id));
            final File backup = new File(root, ".backup-" + UUID.randomUUID());
            if (destination.exists() && !destination.renameTo(backup)) {
                throw new IOException("Could not preserve the existing sticker pack");
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) {
                    backup.renameTo(destination);
                }
                throw new IOException("Could not finish sticker-pack import");
            }
            try {
                final Pack installed = read(destination);
                deleteTree(backup);
                return installed;
            } catch (final IOException | JSONException error) {
                deleteTree(destination);
                if (backup.exists()) {
                    backup.renameTo(destination);
                }
                throw error;
            }
        } catch (final JSONException e) {
            throw new IOException("Could not create sticker-pack manifest", e);
        } finally {
            deleteTree(staging);
        }
    }

    public static Uri materialize(final Context context, final Item item) throws IOException {
        if (item.file() != null) {
            if (!item.file().isFile() || item.file().length() == 0) {
                throw new IOException("Sticker file is unavailable");
            }
            return FileBackend.getUriForFile(context, item.file());
        }
        final File directory = new File(context.getCacheDir(), "sticker-packs/" + STARTER_ID);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create sticker cache");
        }
        final File output =
                new File(
                        directory, item.name().toLowerCase(Locale.ROOT).replace(' ', '-') + ".png");
        if (!output.exists() || output.length() == 0) {
            final Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            if (item.emoji() != null) {
                final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(340f);
                final Paint.FontMetrics metrics = paint.getFontMetrics();
                final float baseline = 256f - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(item.emoji(), 256f, baseline, paint);
            } else {
                final Drawable drawable =
                        AppCompatResources.getDrawable(context, item.drawable());
                if (drawable == null) {
                    bitmap.recycle();
                    throw new IOException("Sticker drawable is unavailable");
                }
                drawable.setBounds(0, 0, 512, 512);
                drawable.draw(canvas);
            }
            try (final FileOutputStream stream = new FileOutputStream(output)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("Could not encode sticker");
                }
            } finally {
                bitmap.recycle();
            }
        }
        return FileBackend.getUriForFile(context, output);
    }

    private static Pack read(final File directory) throws IOException, JSONException {
        final File manifestFile = new File(directory, MANIFEST);
        final JSONObject manifest =
                new JSONObject(Files.asCharSource(manifestFile, StandardCharsets.UTF_8).read());
        final String id = manifest.getString("id");
        if (!validId(id) || !directoryName(id).equals(directory.getName())) {
            throw new IOException("Sticker-pack id does not match its directory");
        }
        final JSONArray sourceItems = manifest.getJSONArray("items");
        if (sourceItems.length() == 0 || sourceItems.length() > MAX_ITEMS) {
            throw new IOException("Sticker pack contains an invalid number of items");
        }
        final List<Item> items = new ArrayList<>();
        for (int i = 0; i < sourceItems.length(); ++i) {
            final JSONObject source = sourceItems.getJSONObject(i);
            final String filename = source.getString("file");
            if (filename.contains("/") || filename.contains("\\") || filename.startsWith(".")) {
                throw new IOException("Unsafe sticker filename");
            }
            final File file = new File(directory, filename);
            if (!file.isFile() || file.length() == 0) {
                throw new IOException("Sticker file is missing");
            }
            items.add(
                    Item.imported(
                            file,
                            source.optString("fallback", "Sticker"),
                            source.optString("name", "Sticker")));
        }
        return new Pack(
                id,
                manifest.optString("name", "Sticker pack"),
                manifest.optString("summary", ""),
                items);
    }

    private static File root(final Context context) {
        return new File(context.getFilesDir(), "sticker-packs");
    }

    private static boolean validId(final String id) {
        return id != null && id.matches("[A-Za-z0-9+/=_-]{1,128}");
    }

    private static String directoryName(final String id) {
        try {
            return com.google.common.io.BaseEncoding.base16()
                    .lowerCase()
                    .encode(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String safeExtension(final String mimeType) {
        final String extension = MimeUtils.guessExtensionFromMimeType(mimeType);
        return extension == null || !extension.matches("[A-Za-z0-9]{1,8}") ? "img" : extension;
    }

    private static void deleteTree(final File file) {
        if (file == null || !file.exists()) {
            return;
        }
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    public record Pack(String id, String name, String summary, List<Item> items) {}

    public record Item(
            @DrawableRes int drawable, File file, String emoji, String fallback, String name) {
        private static Item builtIn(
                @DrawableRes final int drawable, final String fallback, final String name) {
            return new Item(drawable, null, null, fallback, name);
        }

        private static Item builtInEmoji(
                final String emoji, final String fallback, final String name) {
            return new Item(0, null, emoji, fallback, name);
        }

        private static Item imported(final File file, final String fallback, final String name) {
            return new Item(0, file, null, fallback, name);
        }
    }

    public record InstallItem(byte[] bytes, String mimeType, String fallback, String name) {}
}
