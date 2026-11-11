#!/usr/bin/env python3
"""Reads a `uiautomator dump` from stdin and prints, in one line, what has focus and the first few
texts on screen. Used by bench.sh so the CI log shows where the scripted key presses ended up."""
import re
import sys
import xml.etree.ElementTree as ET

raw = sys.stdin.read()
start = raw.find("<?xml")
if start < 0:
    print("    (no UI dump)")
    sys.exit(0)
try:
    root = ET.fromstring(raw[start:raw.rfind("</hierarchy>") + len("</hierarchy>")])
except ET.ParseError:
    print("    (UI dump unreadable)")
    sys.exit(0)

focused, texts = [], []
for n in root.iter("node"):
    label = (n.get("text") or n.get("content-desc") or "").strip().replace("\n", " ")
    if n.get("focused") == "true":
        focused.append(f"{n.get('class', '?').split('.')[-1]}:{label[:40] or '-'}@{n.get('bounds', '')}")
    if label and len(texts) < 10:
        texts.append(label[:28])
print("    focus: " + (" | ".join(focused[:3]) if focused else "none"))
print("    texts: " + " / ".join(texts))
