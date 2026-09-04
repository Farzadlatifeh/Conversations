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
            # Preserve all server-provided settings
            # that we do not want to change.
            submit.append(
                copy.deepcopy(field)
            )

    # Prosody normally advertises these fields.
    # Add anything missing explicitly.
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

    #
    # Publish every pack below current directory:
    #
    # python publish_xep0449.py --all
    #
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

    #
    # Explicit XML paths:
    #
    # python publish_xep0449.py rage/publish.xml ...
    #
    if arguments:
        return [
            Path(argument)
            for argument in arguments
        ]

    #
    # Default: current directory
    #
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

        #
        # Critical:
        # Also supply the desired node configuration
        # during publication.
        #

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

            #
            # Configure the node BEFORE publishing.
            #

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

            #
            # Publish every requested pack.
            #

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

                #
                # If this was a brand-new node,
                # configure it immediately before
                # publishing the second pack.
                #

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

            #
            # Verify what Prosody actually retained.
            #

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

    #
    # All packs should belong to the same
    # publishing XMPP account.
    #

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