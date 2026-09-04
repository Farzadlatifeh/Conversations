# XEP-0449 Sticker Pack Creation and Publishing Guide

This document records the current working method for creating, hosting, generating, publishing, and sharing XEP-0449 sticker packs.

It intentionally excludes old attempts, diagnostics, troubleshooting logs, and Conversations Android client modifications.

---

## 1. Current paths and server details

### Windows sticker root

```text
C:\Users\farza\Downloads\movim\public\stickers
```

Current pack folders:

```text
meme
miho
mochi
movim
noblerimou
raccon
rage
zemarmot
```

Recommended layout:

```text
stickers\
├── make_xep0449.py
├── publish_xep0449.py
├── meme\
│   ├── *.png
│   ├── publish.xml
│   └── share-uri.txt
├── miho\
├── mochi\
├── movim\
├── noblerimou\
├── raccon\
├── rage\
└── zemarmot\
```

### XMPP / web server

Publisher JID:

```text
farzad@intrachat.iddns.ir
```

Public HTTPS host:

```text
intrachat.iddns.ir
```

HTTPS port:

```text
5443
```

SSH server:

```text
farzad@192.168.1.200
```

SSH port:

```text
2222
```

Permanent sticker web root:

```text
/var/www/stickers/
```

Public URL pattern:

```text
https://intrachat.iddns.ir:5443/stickers/<pack>/<filename>
```

Example:

```text
https://intrachat.iddns.ir:5443/stickers/zemarmot/zemarmot%20%281%29.png
```

---

## 2. Python requirements

Install on Windows:

```powershell
python -m pip install pillow slixmpp
```

- `Pillow` reads image dimensions.
- `slixmpp` publishes packs to XMPP PubSub/PEP.

---

## 3. Prepare the sticker images

Each sticker is a PNG file.

Example:

```text
zemarmot (1).png
zemarmot (2).png
...
```

The generator excludes:

```text
icon.png
```

from the actual sticker items.

Do not modify PNG contents after generating the XEP-0449 metadata unless you regenerate and republish the pack, because the pack contains SHA-256 hashes of the files.

---

## 4. Host the sticker images on Debian

Create all current pack directories:

```bash
sudo mkdir -p /var/www/stickers/{meme,miho,mochi,movim,noblerimou,raccon,rage,zemarmot}
```

Permissions:

```bash
sudo chmod 755 /var/www/stickers
sudo chmod 755 /var/www/stickers/*
sudo chmod 644 /var/www/stickers/*/*.png
```

Example SCP upload from Windows:

```powershell
scp -P 2222 "C:\Users\farza\Downloads\movim\public\stickers\zemarmot\*.png" farzad@192.168.1.200:/tmp/
```

Then on Debian:

```bash
sudo mv /tmp/*.png /var/www/stickers/zemarmot/
sudo chmod 644 /var/www/stickers/zemarmot/*.png
```

Important: uppercase `-P` specifies the SSH port.

---

## 5. nginx sticker hosting

The active nginx server on port `5443` must include:

```nginx
location ^~ /stickers/ {
    alias /var/www/stickers/;
}
```

This one location serves every sticker pack.

After editing nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Example mapping:

```text
URL:
https://intrachat.iddns.ir:5443/stickers/zemarmot/zemarmot%20%281%29.png

File:
/var/www/stickers/zemarmot/zemarmot (1).png
```

The URL must return the image directly over HTTPS.

---

## 6. Final `make_xep0449.py`

Save as:

```text
C:\Users\farza\Downloads\movim\public\stickers\make_xep0449.py
```

```python
import sys
import hashlib
import base64
from pathlib import Path
from urllib.parse import quote
import xml.etree.ElementTree as ET

from PIL import Image


PUBSUB = "http://jabber.org/protocol/pubsub"
STICKERS = "urn:xmpp:stickers:0"
FILE_META = "urn:xmpp:file:metadata:0"
HASHES = "urn:xmpp:hashes:2"
SFS = "urn:xmpp:sfs:0"
URL_DATA = "http://jabber.org/protocol/url-data"


def q(ns, name):
    return f"{{{ns}}}{name}"


def sha256_b64(data):
    return base64.b64encode(
        hashlib.sha256(data).digest()
    ).decode("ascii")


def calculate_pack_hash(name, summary, stickers):
    metadata = []

    for element_name, content in (
        ("name", name),
        ("summary", summary),
    ):
        value = bytearray()

        value.extend(element_name.encode("utf-8"))
        value.append(0x1F)

        value.extend(b"")
        value.append(0x1F)

        value.extend(content.encode("utf-8"))
        value.append(0x1F)

        value.append(0x1E)

        metadata.append(bytes(value))

    metadata.sort()

    sticker_values = []

    for sticker in stickers:
        value = bytearray()

        value.extend(sticker["desc"].encode("utf-8"))
        value.append(0x1E)

        hash_values = []

        hash_value = bytearray()

        hash_value.extend(b"sha-256")
        hash_value.append(0x1F)

        hash_value.extend(
            sticker["hash"].encode("utf-8")
        )
        hash_value.append(0x1F)

        hash_value.append(0x1E)

        hash_values.append(bytes(hash_value))
        hash_values.sort()

        for h in hash_values:
            value.extend(h)

        value.append(0x1D)

        sticker_values.append(bytes(value))

    sticker_values.sort()

    canonical = bytearray()

    for value in metadata:
        canonical.extend(value)

    canonical.append(0x1C)

    for value in sticker_values:
        canonical.extend(value)

    canonical.append(0x1C)

    digest = hashlib.sha256(
        bytes(canonical)
    ).digest()

    pack_hash = base64.b64encode(
        digest
    ).decode("ascii")

    pack_id = pack_hash[:24]

    return pack_hash, pack_id


def main():

    if len(sys.argv) != 6:
        print(
            "Usage:\n"
            "python make_xep0449.py "
            "<folder> <base_url> <jid> "
            "<pack_name> <summary>"
        )
        sys.exit(1)

    folder = Path(sys.argv[1])
    base_url = sys.argv[2].rstrip("/") + "/"
    jid = sys.argv[3]
    pack_name = sys.argv[4]
    summary = sys.argv[5]

    if not folder.is_dir():
        print("Folder does not exist:", folder)
        sys.exit(1)

    png_files = sorted(
        [
            p
            for p in folder.iterdir()
            if p.is_file()
            and p.suffix.lower() == ".png"
            and p.name.lower() != "icon.png"
        ],
        key=lambda p: p.name.lower(),
    )

    if not png_files:
        print("No PNG files found.")
        sys.exit(1)

    stickers = []

    for path in png_files:

        data = path.read_bytes()

        with Image.open(path) as image:
            width, height = image.size

        file_hash = sha256_b64(data)
        desc = path.stem

        url = (
            base_url
            + quote(
                path.name,
                safe=""
            )
        )

        stickers.append(
            {
                "path": path,
                "name": path.name,
                "desc": desc,
                "size": len(data),
                "width": width,
                "height": height,
                "hash": file_hash,
                "url": url,
            }
        )

    pack_hash, pack_id = calculate_pack_hash(
        pack_name,
        summary,
        stickers,
    )

    iq = ET.Element(
        "iq",
        {
            "type": "set",
            "to": jid,
            "id": "publish-sticker-pack",
        },
    )

    pubsub = ET.SubElement(
        iq,
        q(PUBSUB, "pubsub"),
    )

    publish = ET.SubElement(
        pubsub,
        q(PUBSUB, "publish"),
        {
            "node": STICKERS,
        },
    )

    pubsub_item = ET.SubElement(
        publish,
        q(PUBSUB, "item"),
        {
            "id": pack_id,
        },
    )

    pack = ET.SubElement(
        pubsub_item,
        q(STICKERS, "pack"),
    )

    name_element = ET.SubElement(
        pack,
        q(STICKERS, "name"),
    )
    name_element.text = pack_name

    summary_element = ET.SubElement(
        pack,
        q(STICKERS, "summary"),
    )
    summary_element.text = summary

    for sticker in stickers:

        sticker_item = ET.SubElement(
            pack,
            q(STICKERS, "item"),
        )

        file_element = ET.SubElement(
            sticker_item,
            q(FILE_META, "file"),
        )

        media_type = ET.SubElement(
            file_element,
            q(FILE_META, "media-type"),
        )
        media_type.text = "image/png"

        desc = ET.SubElement(
            file_element,
            q(FILE_META, "desc"),
        )
        desc.text = sticker["desc"]

        size = ET.SubElement(
            file_element,
            q(FILE_META, "size"),
        )
        size.text = str(
            sticker["size"]
        )

        dimensions = ET.SubElement(
            file_element,
            q(FILE_META, "dimensions"),
        )
        dimensions.text = (
            f'{sticker["width"]}'
            f'x{sticker["height"]}'
        )

        file_hash = ET.SubElement(
            file_element,
            q(HASHES, "hash"),
            {
                "algo": "sha-256",
            },
        )
        file_hash.text = sticker["hash"]

        sources = ET.SubElement(
            sticker_item,
            q(SFS, "sources"),
        )

        ET.SubElement(
            sources,
            q(URL_DATA, "url-data"),
            {
                "target": sticker["url"],
            },
        )

    final_hash = ET.SubElement(
        pack,
        q(HASHES, "hash"),
        {
            "algo": "sha-256",
        },
    )

    final_hash.text = pack_hash

    ET.indent(
        iq,
        space="  "
    )

    tree = ET.ElementTree(iq)

    tree.write(
        "publish.xml",
        encoding="utf-8",
        xml_declaration=False,
    )

    escaped_item = quote(
        pack_id,
        safe=""
    )

    share_uri = (
        f"xmpp:{jid}"
        f"?pubsub"
        f";action=retrieve"
        f";node={STICKERS}"
        f";item={escaped_item}"
    )

    Path(
        "share-uri.txt"
    ).write_text(
        share_uri + "\n",
        encoding="utf-8",
    )

    print()
    print("Done.")
    print(
        "Sticker count :",
        len(stickers)
    )
    print(
        "Pack ID       :",
        pack_id
    )
    print(
        "Pack hash     :",
        pack_hash
    )
    print()
    print("Created:")
    print("  publish.xml")
    print("  share-uri.txt")
    print()
    print("Install/share URI:")
    print(share_uri)


if __name__ == "__main__":
    main()
```

---

## 7. Generate one pack

From the sticker root:

```powershell
cd "C:\Users\farza\Downloads\movim\public\stickers"
```

Example:

```powershell
python .\make_xep0449.py ".\zemarmot" "https://intrachat.iddns.ir:5443/stickers/zemarmot/" "farzad@intrachat.iddns.ir" "Zemarmot" "Zemarmot sticker pack"
```

This creates inside the pack folder:

```text
publish.xml
share-uri.txt
```

Expected output format:

```text
Done.
Sticker count : <count>
Pack ID       : <24-character pack ID>
Pack hash     : <full Base64 SHA-256 hash>

Created:
  publish.xml
  share-uri.txt

Install/share URI:
xmpp:farzad@intrachat.iddns.ir?pubsub;action=retrieve;node=urn:xmpp:stickers:0;item=<pack-id>
```

If the pack name, summary, sticker list, descriptions, or image hashes change, the pack ID can change.

---

## 8. Generate all current packs

From:

```powershell
cd "C:\Users\farza\Downloads\movim\public\stickers"
```

run:

```powershell
python .\make_xep0449.py ".\meme" "https://intrachat.iddns.ir:5443/stickers/meme/" "farzad@intrachat.iddns.ir" "meme" "meme sticker pack"

python .\make_xep0449.py ".\miho" "https://intrachat.iddns.ir:5443/stickers/miho/" "farzad@intrachat.iddns.ir" "miho" "miho sticker pack"

python .\make_xep0449.py ".\mochi" "https://intrachat.iddns.ir:5443/stickers/mochi/" "farzad@intrachat.iddns.ir" "mochi" "mochi sticker pack"

python .\make_xep0449.py ".\movim" "https://intrachat.iddns.ir:5443/stickers/movim/" "farzad@intrachat.iddns.ir" "movim" "movim sticker pack"

python .\make_xep0449.py ".\noblerimou" "https://intrachat.iddns.ir:5443/stickers/noblerimou/" "farzad@intrachat.iddns.ir" "noblerimou" "noblerimou sticker pack"

python .\make_xep0449.py ".\raccon" "https://intrachat.iddns.ir:5443/stickers/raccon/" "farzad@intrachat.iddns.ir" "raccon" "raccon sticker pack"

python .\make_xep0449.py ".\rage" "https://intrachat.iddns.ir:5443/stickers/rage/" "farzad@intrachat.iddns.ir" "rage" "rage sticker pack"

python .\make_xep0449.py ".\zemarmot" "https://intrachat.iddns.ir:5443/stickers/zemarmot/" "farzad@intrachat.iddns.ir" "Zemarmot" "Zemarmot sticker pack"
```

---

## 9. Final `publish_xep0449.py`

Save one copy at:

```text
C:\Users\farza\Downloads\movim\public\stickers\publish_xep0449.py
```

This script:
- finds all `*/publish.xml` files with `--all`
- configures the XEP-0449 PEP node
- sets `access_model = open`
- enables persistent items
- sets `max_items = 256`
- publishes each pack as a separate item
- verifies that all requested pack IDs remain stored

```python
import asyncio
import copy
import getpass
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

import slixmpp
from slixmpp.exceptions import IqError, IqTimeout


PUBSUB_NS = "http://jabber.org/protocol/pubsub"
OWNER_NS = "http://jabber.org/protocol/pubsub#owner"
DATA_NS = "jabber:x:data"
STICKERS_NS = "urn:xmpp:stickers:0"

NODE = STICKERS_NS
MAX_ITEMS = "256"

NODE_CONFIG_FORM = "http://jabber.org/protocol/pubsub#node_config"
PUBLISH_OPTIONS_FORM = "http://jabber.org/protocol/pubsub#publish-options"


def q(namespace, name):
    return f"{{{namespace}}}{name}"


def add_field(form, var, value, field_type=None):
    attrs = {"var": var}

    if field_type:
        attrs["type"] = field_type

    field = ET.SubElement(
        form,
        q(DATA_NS, "field"),
        attrs,
    )

    values = value if isinstance(value, (list, tuple)) else [value]

    for entry in values:
        element = ET.SubElement(
            field,
            q(DATA_NS, "value"),
        )
        element.text = str(entry)


def desired_node_values():
    return {
        "pubsub#access_model": "open",
        "pubsub#persist_items": "1",
        "pubsub#max_items": MAX_ITEMS,
    }


def build_publish_options():
    form = ET.Element(
        q(DATA_NS, "x"),
        {"type": "submit"},
    )

    add_field(
        form,
        "FORM_TYPE",
        PUBLISH_OPTIONS_FORM,
        "hidden",
    )

    for var, value in desired_node_values().items():
        add_field(
            form,
            var,
            value,
        )

    return form


def build_config_submission(server_form):
    submit = ET.Element(
        q(DATA_NS, "x"),
        {"type": "submit"},
    )

    add_field(
        submit,
        "FORM_TYPE",
        NODE_CONFIG_FORM,
        "hidden",
    )

    overrides = desired_node_values()
    seen = set()

    for field in server_form.findall(
        q(DATA_NS, "field")
    ):
        var = field.get("var")

        if not var or var == "FORM_TYPE":
            continue

        seen.add(var)

        if var in overrides:
            add_field(
                submit,
                var,
                overrides[var],
            )
        else:
            submit.append(
                copy.deepcopy(field)
            )

    for var, value in overrides.items():
        if var not in seen:
            add_field(
                submit,
                var,
                value,
            )

    return submit


def load_publish_xml(path):
    tree = ET.parse(path)
    root = tree.getroot()

    pubsub_item = root.find(
        f".//{q(PUBSUB_NS, 'item')}"
    )

    if pubsub_item is None:
        raise ValueError(
            f"{path}: no namespaced PubSub <item> found. "
            "Regenerate this publish.xml using the corrected "
            "make_xep0449.py."
        )

    pack_id = pubsub_item.get("id")

    if not pack_id:
        raise ValueError(
            f"{path}: PubSub item has no id."
        )

    pack = pubsub_item.find(
        q(STICKERS_NS, "pack")
    )

    if pack is None:
        raise ValueError(
            f"{path}: no XEP-0449 <pack> payload found."
        )

    name_element = pack.find(
        q(STICKERS_NS, "name")
    )

    if (
        name_element is not None
        and name_element.text
    ):
        pack_name = name_element.text.strip()
    else:
        pack_name = path.parent.name

    jid = root.get("to")

    return {
        "path": path,
        "jid": jid,
        "id": pack_id,
        "name": pack_name,
        "pack": pack,
    }


def discover_publish_files(arguments):

    if arguments == ["--all"]:

        files = sorted(
            Path(".").glob("*/publish.xml"),
            key=lambda path: str(path).lower(),
        )

        if not files:
            raise FileNotFoundError(
                "No */publish.xml files were found."
            )

        return files

    if arguments:
        return [
            Path(argument)
            for argument in arguments
        ]

    return [
        Path("publish.xml")
    ]


def is_item_not_found(error):
    return "item-not-found" in str(error.iq)


class StickerPublisher(slixmpp.ClientXMPP):

    def __init__(
        self,
        jid,
        password,
        packs,
    ):
        super().__init__(
            jid,
            password,
        )

        self.packs = packs

        self.add_event_handler(
            "session_start",
            self.session_start,
        )

    async def get_node_config_form(self):

        iq = self.make_iq_get(
            ito=str(self.boundjid.bare)
        )

        owner = ET.Element(
            q(OWNER_NS, "pubsub")
        )

        ET.SubElement(
            owner,
            q(OWNER_NS, "configure"),
            {
                "node": NODE,
            },
        )

        iq.xml.append(owner)

        result = await iq.send(
            timeout=30
        )

        configure = result.xml.find(
            f".//{q(OWNER_NS, 'configure')}"
        )

        if configure is None:
            raise RuntimeError(
                "Server returned no node configuration."
            )

        form = configure.find(
            q(DATA_NS, "x")
        )

        if form is None:
            raise RuntimeError(
                "Server returned no configuration form."
            )

        return form

    async def set_node_config(
        self,
        submit_form,
    ):

        iq = self.make_iq_set(
            ito=str(self.boundjid.bare)
        )

        owner = ET.Element(
            q(OWNER_NS, "pubsub")
        )

        configure = ET.SubElement(
            owner,
            q(OWNER_NS, "configure"),
            {
                "node": NODE,
            },
        )

        configure.append(
            submit_form
        )

        iq.xml.append(owner)

        await iq.send(
            timeout=30
        )

    async def configure_existing_node(self):

        try:
            form = await self.get_node_config_form()

        except IqError as error:

            if is_item_not_found(error):
                return False

            raise

        submit = build_config_submission(
            form
        )

        await self.set_node_config(
            submit
        )

        return True

    async def publish_one(
        self,
        pack_info,
    ):

        iq = self.make_iq_set(
            ito=str(self.boundjid.bare)
        )

        pubsub = ET.Element(
            q(PUBSUB_NS, "pubsub")
        )

        publish = ET.SubElement(
            pubsub,
            q(PUBSUB_NS, "publish"),
            {
                "node": NODE,
            },
        )

        item = ET.SubElement(
            publish,
            q(PUBSUB_NS, "item"),
            {
                "id": pack_info["id"],
            },
        )

        item.append(
            copy.deepcopy(
                pack_info["pack"]
            )
        )

        publish_options = ET.SubElement(
            pubsub,
            q(PUBSUB_NS, "publish-options"),
        )

        publish_options.append(
            build_publish_options()
        )

        iq.xml.append(
            pubsub
        )

        await iq.send(
            timeout=30
        )

    async def fetch_item_ids(self):

        iq = self.make_iq_get(
            ito=str(self.boundjid.bare)
        )

        pubsub = ET.Element(
            q(PUBSUB_NS, "pubsub")
        )

        ET.SubElement(
            pubsub,
            q(PUBSUB_NS, "items"),
            {
                "node": NODE,
            },
        )

        iq.xml.append(
            pubsub
        )

        result = await iq.send(
            timeout=30
        )

        items = result.xml.find(
            f".//{q(PUBSUB_NS, 'items')}"
        )

        if items is None:
            return []

        return [
            item.get("id")
            for item in items.findall(
                q(PUBSUB_NS, "item")
            )
            if item.get("id")
        ]

    async def session_start(self, event):

        try:

            print()
            print(
                "Connected as:",
                self.boundjid.full,
            )
            print()

            print(
                "Configuring sticker PEP node..."
            )

            existed = (
                await self.configure_existing_node()
            )

            if existed:

                print(
                    "Node configured:"
                )
                print(
                    "  access_model = open"
                )
                print(
                    "  persist_items = true"
                )
                print(
                    f"  max_items = {MAX_ITEMS}"
                )

            else:

                print(
                    "Sticker node does not exist yet."
                )
                print(
                    "The first publication will create "
                    "it with the correct settings."
                )

            print()

            for index, pack_info in enumerate(
                self.packs,
                start=1,
            ):

                print(
                    f"[{index}/{len(self.packs)}] "
                    f"Publishing {pack_info['name']}"
                )

                print(
                    "  Pack ID:",
                    pack_info["id"],
                )

                await self.publish_one(
                    pack_info
                )

                print(
                    "  Published successfully."
                )

                if not existed:

                    configured = (
                        await self.configure_existing_node()
                    )

                    if not configured:
                        raise RuntimeError(
                            "The node was created but "
                            "could not be configured."
                        )

                    existed = True

                    print(
                        "  New node confirmed:"
                    )
                    print(
                        "    access_model = open"
                    )
                    print(
                        "    persist_items = true"
                    )
                    print(
                        f"    max_items = {MAX_ITEMS}"
                    )

                print()

            print(
                "Verifying stored PubSub items..."
            )

            item_ids = (
                await self.fetch_item_ids()
            )

            print(
                "Stored item count:",
                len(item_ids),
            )

            for item_id in item_ids:
                print(
                    " ",
                    item_id,
                )

            expected = {
                pack["id"]
                for pack in self.packs
            }

            missing = sorted(
                expected.difference(
                    item_ids
                )
            )

            if missing:

                print()
                print(
                    "ERROR:"
                )
                print(
                    "These packs were published "
                    "but are not currently retained:"
                )

                for item_id in missing:
                    print(
                        " ",
                        item_id,
                    )

                raise RuntimeError(
                    "One or more sticker packs "
                    "were not retained by Prosody."
                )

            print()
            print(
                "SUCCESS"
            )

            print(
                "The sticker node is now configured "
                "for multiple persistent public packs."
            )

            print(
                "All requested pack IDs are present."
            )

        except IqError as error:

            print()
            print(
                "XMPP SERVER ERROR:"
            )

            print(
                error.iq
            )

        except IqTimeout:

            print()
            print(
                "ERROR: XMPP server did not respond."
            )

        except Exception as error:

            print()
            print(
                "ERROR:",
                repr(error),
            )

        finally:

            self.disconnect()


def main():

    try:

        publish_files = (
            discover_publish_files(
                sys.argv[1:]
            )
        )

        packs = [
            load_publish_xml(path)
            for path in publish_files
        ]

    except Exception as error:

        print(
            "ERROR:",
            error,
        )

        sys.exit(1)

    jids = {
        pack["jid"]
        for pack in packs
        if pack["jid"]
    }

    if len(jids) > 1:

        print(
            "ERROR: publish.xml files contain "
            "different destination JIDs:"
        )

        for jid in sorted(jids):
            print(
                " ",
                jid,
            )

        sys.exit(1)

    if jids:

        jid = next(
            iter(jids)
        )

        print(
            "XMPP JID:",
            jid,
        )

    else:

        jid = input(
            "XMPP JID: "
        ).strip()

    print()
    print(
        "Packs to publish:"
    )

    for pack in packs:

        print(
            f"  {pack['name']} "
            f"-> {pack['id']}"
        )

    print()

    password = getpass.getpass(
        "XMPP password: "
    )

    xmpp = StickerPublisher(
        jid,
        password,
        packs,
    )

    xmpp.connect()

    asyncio.get_event_loop().run_until_complete(
        xmpp.disconnected
    )


if __name__ == "__main__":
    main()
```

---

## 10. Publish all current packs

From:

```powershell
cd "C:\Users\farza\Downloads\movim\public\stickers"
```

run:

```powershell
python .\publish_xep0449.py --all
```

The script finds:

```text
meme\publish.xml
miho\publish.xml
mochi\publish.xml
movim\publish.xml
noblerimou\publish.xml
raccon\publish.xml
rage\publish.xml
zemarmot\publish.xml
```

It asks for the XMPP password once.

Successful completion:

```text
Verifying stored PubSub items...
Stored item count: 8

SUCCESS
The sticker node is now configured for multiple persistent public packs.
All requested pack IDs are present.
```

All sticker packs are separate PubSub items under the same node:

```text
urn:xmpp:stickers:0
```

Do not create a different PubSub node for each pack.

---

## 11. Publish only one pack

From the sticker root:

```powershell
python .\publish_xep0449.py ".\zemarmot\publish.xml"
```

If the script and `publish.xml` are in the same current directory:

```powershell
python .\publish_xep0449.py
```

The default input is:

```text
publish.xml
```

---

## 12. Share URI

Each pack has:

```text
share-uri.txt
```

Format:

```text
xmpp:farzad@intrachat.iddns.ir?pubsub;action=retrieve;node=urn:xmpp:stickers:0;item=<pack-id>
```

The URI is reusable.

It is not a one-time link.

---

## 13. Updating an existing pack

If any of these change:

- PNG bytes
- filename
- sticker list
- description
- pack name
- pack summary

run the complete generation process again.

Workflow:

```text
1. Update the PNG files.
2. Upload the updated PNG files to /var/www/stickers/<pack>/.
3. Run make_xep0449.py again.
4. Use the newly generated publish.xml.
5. Run publish_xep0449.py.
6. Use the new share-uri.txt if the pack ID changed.
```

Never keep using an old URI if regeneration produced a different pack ID.

---

## 14. New-pack example

For a new pack called `example`:

### Windows

Create:

```text
C:\Users\farza\Downloads\movim\public\stickers\example
```

Put PNG files inside it.

### Debian

```bash
sudo mkdir -p /var/www/stickers/example
sudo chmod 755 /var/www/stickers/example
```

Upload/copy the PNGs, then:

```bash
sudo chmod 644 /var/www/stickers/example/*.png
```

### Generate

From Windows:

```powershell
cd "C:\Users\farza\Downloads\movim\public\stickers"

python .\make_xep0449.py ".\example" "https://intrachat.iddns.ir:5443/stickers/example/" "farzad@intrachat.iddns.ir" "Example" "Example sticker pack"
```

Generated files:

```text
example\publish.xml
example\share-uri.txt
```

### Publish

```powershell
python .\publish_xep0449.py ".\example\publish.xml"
```

### Share

Use:

```text
example\share-uri.txt
```

---

## 15. Key rules

1. Sticker images must remain reachable over HTTPS.

2. The hosted file bytes must match the bytes used when generating their SHA-256 hashes.

3. `icon.png` is excluded from sticker items.

4. One sticker pack = one PubSub item.

5. All packs use:

```text
urn:xmpp:stickers:0
```

6. Current node configuration:

```text
access_model = open
persist_items = true
max_items = 256
```

7. The share URI is reusable.

8. Regenerating a pack may generate a different pack ID.

9. If the pack ID changes, distribute the new `share-uri.txt`.

10. Current normal bulk-publication command:

```powershell
cd "C:\Users\farza\Downloads\movim\public\stickers"
python .\publish_xep0449.py --all
```

11. The expected known-good final state for the current eight packs is:

```text
Stored item count: 8
SUCCESS
The sticker node is now configured for multiple persistent public packs.
All requested pack IDs are present.
```
