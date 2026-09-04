import sys
import os
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

XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"


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

        # No xml:lang
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

        # desc
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
            "Usage:"
            "\n"
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

    #
    # Construct namespace-correct XEP-0449 XML.
    #

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