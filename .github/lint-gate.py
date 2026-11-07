#!/usr/bin/env python3
"""Fails the build only on lint issues that crash or misbehave on real devices.

Everything else stays in the attached report for reference. Usage: lint-gate.py <lint.xml>
"""
import sys
import xml.etree.ElementTree as ET

# Calls to APIs newer than minSdk, missing permissions or manifest classes, and so on.
CRITICAL = {
    "NewApi",
    "MissingPermission",
    "MissingClass",
    "MissingSuperCall",
    "UnspecifiedRegisterReceiverFlag",
}

path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except (OSError, ET.ParseError) as e:
    print(f"::error::Lint report missing or unreadable ({e}).")
    sys.exit(1)

total = 0
bad = []
for issue in root.iter("issue"):
    total += 1
    if issue.get("id") in CRITICAL:
        loc = issue.find("location")
        where = f"{loc.get('file', '?')}:{loc.get('line', '?')}" if loc is not None else "?"
        bad.append((issue.get("id"), where, issue.get("message", "")))

for i, where, msg in bad:
    print(f"::error file={where.split(':')[0]}::{i} at {where}: {msg}")
print(f"Lint: {total} issue(s) in the report, {len(bad)} critical.")
sys.exit(1 if bad else 0)
