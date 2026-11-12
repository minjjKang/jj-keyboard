"""Capture the explicitly selected Android reference surface via adb, without changing defaults."""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--directory", required=True)
parser.add_argument("command", choices=["capture", "tap", "back"])
parser.add_argument("value", nargs="?")
args = parser.parse_args()
directory = (root / args.directory).resolve()
if not directory.is_relative_to(root / "docs"):
    raise SystemExit("Reference evidence must stay under docs/.")
directory.mkdir(parents=True, exist_ok=True)
adb = root / ".tools/android-sdk/platform-tools/adb.exe"

def run(*command):
    return subprocess.run([str(adb), "-s", args.serial, *command], check=True, capture_output=True).stdout.decode("utf-8", errors="replace").strip()

if args.command == "capture":
    if not args.value or not re.fullmatch(r"[A-Za-z0-9_-]+", args.value):
        raise SystemExit("Use an alphanumeric capture name.")
    policy = run("shell", "dumpsys", "window", "policy")
    if re.search(r"\b(?:mIsShowing|mShowingLockscreen)=true\b", policy):
        raise SystemExit("Reference is locked. Unlock it on the device before capturing.")
    run("shell", "uiautomator", "dump", "/data/local/tmp/minjj-reference.xml")
    run("pull", "/data/local/tmp/minjj-reference.xml", str(directory / f"{args.value}.xml"))
    run("shell", "screencap", "-p", "/data/local/tmp/minjj-reference.png")
    run("pull", "/data/local/tmp/minjj-reference.png", str(directory / f"{args.value}.png"))
    (directory / "latest.txt").write_text(args.value, encoding="utf-8")
    tree = ET.parse(directory / f"{args.value}.xml")
    nodes = [{key: node.get(key) for key in ("text", "content-desc", "resource-id", "bounds", "checked", "clickable")}
             for node in tree.iter("node") if node.get("text") or node.get("content-desc")]
    for node in nodes:
        print(json.dumps([node["text"] or node["content-desc"], node["bounds"], node["checked"]], ensure_ascii=False))
elif args.command == "tap":
    latest = (directory / "latest.txt").read_text(encoding="utf-8")
    tree = ET.parse(directory / f"{latest}.xml")
    matches = [node for node in tree.iter("node") if args.value in (node.get("text"), node.get("content-desc"))]
    if len(matches) != 1:
        raise SystemExit(f"Expected one observed label, found {len(matches)}: {args.value}")
    values = list(map(int, re.findall(r"\d+", matches[0].get("bounds"))))
    x1, y1, x2, y2 = values
    run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.4)
    print("Tapped observed label:", args.value)
else:
    run("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.4)
