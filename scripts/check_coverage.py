#!/usr/bin/env python3
"""Parse JaCoCo XML report for :app and print exact per-file + total coverage and missed lines."""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_XML = Path("/Users/mac/Aircheck/app/build/reports/jacoco/jacocoFullCoverageReport/jacocoFullCoverageReport.xml")


def counter(elem: ET.Element, kind: str) -> tuple[int, int]:
    for c in elem.findall("counter"):
        if c.attrib.get("type") == kind:
            return int(c.attrib["covered"]), int(c.attrib["missed"])
    return 0, 0


def pct(covered: int, missed: int) -> float:
    total = covered + missed
    return 100.0 if total == 0 else (covered * 100.0 / total)


def main() -> int:
    xml_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_XML
    show_missed = "--missed" in sys.argv
    if not xml_path.is_file():
        print(f"ERROR: JaCoCo XML not found: {xml_path}", file=sys.stderr)
        return 2

    tree = ET.parse(xml_path)
    root = tree.getroot()

    rows = []
    for pkg in root.findall("package"):
        pkg_name = pkg.attrib["name"].replace("/", ".")
        for sf in pkg.findall("sourcefile"):
            name = f"{pkg_name}.{sf.attrib['name']}"
            lc, lm = counter(sf, "LINE")
            ic, im = counter(sf, "INSTRUCTION")
            bc, bm = counter(sf, "BRANCH")
            missed_lines = [
                int(ln.attrib["nr"])
                for ln in sf.findall("line")
                if int(ln.attrib.get("ci", "0")) == 0
            ]
            rows.append((pct(lc, lm), name, lc, lm, ic, im, bc, bm, missed_lines))

    rows.sort(key=lambda r: (r[0], r[1]))

    print(f"{'FILE':62s} {'LINE':>14s} {'INSTR':>14s} {'BRANCH':>10s}")
    print("-" * 104)
    tot_lc = tot_lm = tot_ic = tot_im = tot_bc = tot_bm = 0
    for lp, name, lc, lm, ic, im, bc, bm, missed in rows:
        tot_lc += lc
        tot_lm += lm
        tot_ic += ic
        tot_im += im
        tot_bc += bc
        tot_bm += bm
        line_str = f"{lc}/{lc+lm} ({lp:5.1f}%)"
        inst_str = f"{ic}/{ic+im} ({pct(ic, im):5.1f}%)"
        br_str = f"{pct(bc, bm):5.1f}%"
        print(f"{name:62s} {line_str:>14s} {inst_str:>14s} {br_str:>10s}")
        if show_missed and missed:
            preview = ",".join(str(x) for x in missed[:30])
            suffix = f" ... (+{len(missed)-30} more)" if len(missed) > 30 else ""
            print(f"    MISSED LINES ({len(missed)}): {preview}{suffix}")

    print("-" * 104)
    print(
        f"{'TOTAL (' + str(len(rows)) + ' files)':62s} "
        f"{f'{tot_lc}/{tot_lc+tot_lm} ({pct(tot_lc, tot_lm):5.1f}%)':>14s} "
        f"{f'{tot_ic}/{tot_ic+tot_im} ({pct(tot_ic, tot_im):5.1f}%)':>14s} "
        f"{f'{pct(tot_bc, tot_bm):5.1f}%':>10s}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
