package im.conversations.android.xmpp.model.stickers;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;
import java.util.ArrayList;
import java.util.List;

@XmlElement
public final class Pack extends Extension {

    public Pack() {
        super(Pack.class);
    }

    public String getDisplayName() {
        final Element child = findChild("name", Namespace.STICKERS);
        return child == null ? null : child.getContent();
    }

    public String getSummary() {
        final Element child = findChild("summary", Namespace.STICKERS);
        return child == null ? null : child.getContent();
    }

    public boolean isRestricted() {
        return hasChild("restricted", Namespace.STICKERS);
    }

    public List<Element> getItems() {
        final List<Element> items = new ArrayList<>();
        for (final Element child : getChildren()) {
            if ("item".equals(child.getName()) && Namespace.STICKERS.equals(child.getNamespace())) {
                items.add(child);
            }
        }
        return items;
    }
}
