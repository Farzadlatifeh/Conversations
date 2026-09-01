package eu.siacs.conversations.ui.stickers;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;
import androidx.annotation.NonNull;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import eu.siacs.conversations.R;
import java.util.List;

/** Presentation-only sticker picker. Pack IO and validation remain outside the UI layer. */
public final class StickerPickerDialog {

    public interface Listener {
        void onStickerSelected(StickerPack.Pack pack, StickerPack.Item sticker);

        void onImportPackRequested();
    }

    private StickerPickerDialog() {}

    public static void show(
            @NonNull final Context context,
            @NonNull final List<StickerPack.Pack> packs,
            @NonNull final Listener listener) {
        if (packs.isEmpty()) {
            listener.onImportPackRequested();
            return;
        }
        final View root =
                LayoutInflater.from(context).inflate(R.layout.dialog_sticker_picker, null, false);
        final GridLayout grid = root.findViewById(R.id.sticker_grid);
        final TabLayout tabs = root.findViewById(R.id.sticker_picker_tabs);
        final ViewFlipper pages = root.findViewById(R.id.sticker_picker_pages);
        final LinearLayout installedPacks = root.findViewById(R.id.installed_sticker_packs);
        final MaterialButton importPack = root.findViewById(R.id.import_sticker_pack_button);
        final var selectedPack = new StickerPack.Pack[] {packs.get(0)};

        final var dialog =
                new MaterialAlertDialogBuilder(context)
                        .setTitle(selectedPack[0].name())
                        .setView(root)
                        .create();

        final Runnable renderSelected =
                () -> {
                    dialog.setTitle(selectedPack[0].name());
                    renderGrid(context, grid, selectedPack[0], listener, dialog::dismiss);
                };
        renderSelected.run();

        for (final StickerPack.Pack pack : packs) {
            final MaterialButton button = new MaterialButton(context, null);
            button.setText(pack.name());
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setOnClickListener(
                    ignored -> {
                        selectedPack[0] = pack;
                        renderSelected.run();
                        final TabLayout.Tab stickersTab = tabs.getTabAt(0);
                        if (stickersTab != null) {
                            stickersTab.select();
                        }
                    });
            installedPacks.addView(
                    button,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        tabs.addOnTabSelectedListener(
                new TabLayout.OnTabSelectedListener() {
                    @Override
                    public void onTabSelected(final TabLayout.Tab tab) {
                        pages.setDisplayedChild(tab.getPosition());
                    }

                    @Override
                    public void onTabUnselected(final TabLayout.Tab tab) {}

                    @Override
                    public void onTabReselected(final TabLayout.Tab tab) {}
                });
        importPack.setOnClickListener(
                ignored -> {
                    dialog.dismiss();
                    listener.onImportPackRequested();
                });
        dialog.show();
    }

    private static void renderGrid(
            final Context context,
            final GridLayout grid,
            final StickerPack.Pack pack,
            final Listener listener,
            final Runnable dismiss) {
        grid.removeAllViews();
        final int itemSize = dp(context, 80);
        final int padding = dp(context, 6);
        for (final StickerPack.Item sticker : pack.items()) {
            final View button;
            if (sticker.emoji() != null) {
                final TextView emoji = new TextView(context);
                emoji.setText(sticker.emoji());
                emoji.setTextSize(42);
                emoji.setGravity(Gravity.CENTER);
                emoji.setBackgroundColor(Color.TRANSPARENT);
                button = emoji;
            } else {
                final ImageButton image = new ImageButton(context);
                image.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
                image.setBackgroundColor(Color.TRANSPARENT);
                image.setPadding(padding, padding, padding, padding);
                if (sticker.drawable() != 0) {
                    image.setImageResource(sticker.drawable());
                } else if (sticker.file() != null) {
                    image.setImageURI(Uri.fromFile(sticker.file()));
                }
                button = image;
            }
            button.setContentDescription(sticker.name());
            button.setOnClickListener(
                    ignored -> {
                        listener.onStickerSelected(pack, sticker);
                        dismiss.run();
                    });
            final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = itemSize;
            params.height = itemSize;
            params.setMargins(padding, padding, padding, padding);
            grid.addView(button, params);
        }
    }

    private static int dp(final Context context, final int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
