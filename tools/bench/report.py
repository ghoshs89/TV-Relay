#!/usr/bin/env python3
"""Sums up a logcat dump from tools/bench/bench.sh: frame times per stretch of the run, plus the
list, first-picture and warning lines the app wrote. Prints plain text for the CI log."""
import re
import sys
from collections import OrderedDict

LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:\s(.*)$")
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
        if "Catalog" in n and "ready after" not in n and "Read saved" not in n and "several versions" not in n and "Checked new" not in n:
            continue
        print(n)
        seen += 1
        if seen > 120:
            print("... (cut)")
            break


if __name__ == "__main__":
    main(sys.argv[1])
