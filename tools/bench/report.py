#!/usr/bin/env python3
"""Sums up a logcat dump from tools/bench/bench.sh: frame times per stretch of the run, plus the
list, first-picture and warning lines the app wrote. Prints plain text for the CI log.

    report.py <logcat.txt>                       frame times and what the app logged
    report.py --cold <saved.txt> <json.txt>      the cold-start block: the saved copy against the JSON path
"""
import re
import sys
from collections import OrderedDict

LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:\s(.*)$")
CHANNELS_READY = re.compile(r"Channels ready after (\d+) ms, ([\d.]+) s after start")
ALL_READY = re.compile(r"all lists after (\d+) ms, ([\d.]+) s after start")
# The saved copy has to beat the JSON path by at least this factor for channels on screen.
COLD_GATE = 4.0
# Catalog lines worth printing; the rest of that tag is chatter.
CATALOG_KEEP = (
    "ready after", "Read saved", "several versions", "Checked new",
    "Library from saved copy", "Saved copy not used", "Saved a copy", "Not saving a copy",
    "Couldn't save a copy", "Reused",
)
FRAMES = re.compile(
    r"\[(?P<label>[^\]]*)\] (?P<frames>\d+) frames, (?P<o16>\d+) over 16 ms, (?P<o32>\d+) over 32 ms, "
    r"(?P<o100>\d+) over 100 ms, 95% under (?P<p95>[\d.]+) ms, worst (?P<worst>[\d.]+) ms"
)


def main(path):
    stretch = "startup"
    totals = OrderedDict()
    notes = []
    for raw in open(path, errors="replace"):
        m = LINE.match(raw.rstrip("\n"))
        if not m:
            continue
        stamp, level, tag, msg = m.groups()
        if tag == "RelayBench" and msg.startswith("stretch:"):
            stretch = msg.split(":", 1)[1].strip()
            continue
        if tag == "Relay/Frames":
            f = FRAMES.search(msg)
            if f:
                t = totals.setdefault(stretch, dict(frames=0, o16=0, o32=0, o100=0, p95=0.0, worst=0.0))
                t["frames"] += int(f["frames"])
                t["o16"] += int(f["o16"])
                t["o32"] += int(f["o32"])
                t["o100"] += int(f["o100"])
                t["p95"] = max(t["p95"], float(f["p95"]))
                t["worst"] = max(t["worst"], float(f["worst"]))
            continue
        if tag in ("Relay/Live", "Relay/Library", "Relay/Player", "Relay/Catalog", "Relay/Bench") or level in "WE" and tag.startswith("Relay/"):
            notes.append(f"{stamp} {level} [{stretch}] {tag[6:]}: {msg}")

    print("Frame times per stretch (frames are drawn on a build server's emulator, so compare builds, not TVs)")
    print(f"{'stretch':<20}{'frames':>8}{'>16ms':>8}{'>32ms':>8}{'>100ms':>8}{'p95 max':>9}{'worst':>9}")
    for name, t in totals.items():
        print(f"{name:<20}{t['frames']:>8}{t['o16']:>8}{t['o32']:>8}{t['o100']:>8}{t['p95']:>8.1f}ms{t['worst']:>7.0f}ms")
    print()
    print("What the app logged")
    seen = 0
    for n in notes:
        if "Catalog" in n and not any(k in n for k in CATALOG_KEEP):
            continue
        print(n)
        seen += 1
        if seen > 120:
            print("... (cut)")
            break


def cold_numbers(path):
    """What one restart logged: when channels and all lists were on screen (ms and seconds since the
    process started), whether the saved copy was used, and the Catalog lines that explain it."""
    got = dict(channels_ms=None, channels_s=None, all_ms=None, all_s=None, from_copy=False, lines=[])
    try:
        text = open(path, errors="replace").read()
    except OSError:
        return got
    for raw in text.splitlines():
        m = LINE.match(raw)
        if not m:
            continue
        stamp, level, tag, msg = m.groups()
        if tag != "Relay/Catalog":
            continue
        c = CHANNELS_READY.search(msg)
        if c and got["channels_s"] is None:
            got["channels_ms"], got["channels_s"] = int(c.group(1)), float(c.group(2))
        a = ALL_READY.search(msg)
        if a and got["all_s"] is None:
            got["all_ms"], got["all_s"] = int(a.group(1)), float(a.group(2))
        if msg.startswith("Library from saved copy"):
            got["from_copy"] = True
        if any(k in msg for k in CATALOG_KEEP):
            got["lines"].append(f"{stamp} {level} {msg}")
    return got


def cold(saved_path, json_path):
    saved, js = cold_numbers(saved_path), cold_numbers(json_path)
    print("Cold start: the saved copy of the library against the JSON path (seconds from process start, one emulator, file cache dropped before each)")
    print(f"{'':<14}{'channels on screen':>20}{'all lists':>12}   how it started")
    ok = True
    for name, r in (("saved copy", saved), ("JSON path", js)):
        if r["channels_s"] is None or r["all_s"] is None:
            print(f"{name:<14}{'not logged':>20}{'':>12}")
            ok = False
            continue
        how = "from the saved copy" if r["from_copy"] else "from the provider's JSON"
        print(f"{name:<14}{r['channels_s']:>19.1f}s{r['all_s']:>11.1f}s   {how}")
    if ok:
        ratio_ch = js["channels_s"] / saved["channels_s"] if saved["channels_s"] else 0.0
        ratio_all = js["all_s"] / saved["all_s"] if saved["all_s"] else 0.0
        print(f"{'speed-up':<14}{ratio_ch:>19.1f}x{ratio_all:>11.1f}x")
        if not saved["from_copy"]:
            verdict = "n/a: the first restart did not use a saved copy (a build without one, or the copy was not written)"
        elif js["from_copy"]:
            verdict = "n/a: the saved copy was still there for the second restart, so both took the same path"
        elif ratio_ch >= COLD_GATE:
            verdict = f"PASS (channels on screen {ratio_ch:.1f}x faster, needs {COLD_GATE:.0f}x)"
        else:
            verdict = f"FAIL (channels on screen {ratio_ch:.1f}x faster, needs {COLD_GATE:.0f}x)"
        print(f"Cold-start gate: {verdict}")
        print(f"COLD saved_channels_s={saved['channels_s']} json_channels_s={js['channels_s']} "
              f"saved_all_s={saved['all_s']} json_all_s={js['all_s']} ratio_channels={ratio_ch:.2f} ratio_all={ratio_all:.2f}")
    print()
    for name, r in (("saved copy restart", saved), ("JSON path restart", js)):
        print(f"What the app logged, {name}")
        for l in r["lines"][:20]:
            print(f"  {l}")
    print()


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "--cold":
        cold(sys.argv[2], sys.argv[3])
    else:
        main(sys.argv[1])
