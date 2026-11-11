#!/usr/bin/env python3
"""Reads a `uiautomator dump` from stdin and prints the centre ("x y") of the first node whose text
is exactly the argument. Prints nothing when there is none. Used by bench.sh to open screens by
tapping their labels instead of counting remote key presses."""
import sys
import xml.etree.ElementTree as ET

want = sys.argv[1]
raw = sys.stdin.read()
start = raw.find("<?xml")
if start < 0:
    sys.exit(0)
try:
    root = ET.fromstring(raw[start:raw.rfind("</hierarchy>") + len("</hierarchy>")])
except ET.ParseError:
    sys.exit(0)
for n in root.iter("node"):
    if (n.get("text") or "").strip() == want or (n.get("content-desc") or "").strip() == want:
        b = n.get("bounds", "")
        try:
            a, c = b.strip("[]").split("][")
            x1, y1 = (int(v) for v in a.split(","))
            x2, y2 = (int(v) for v in c.split(","))
        except ValueError:
            continue
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
