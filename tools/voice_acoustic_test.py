#!/usr/bin/env python3
"""Run acoustic voice-input trials using macOS playback and an ADB device."""

import argparse
import json
import re
import subprocess
import tempfile
import threading
import time
import unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path


PACKAGE = "org.fcitx.fcitx5.android.voice.debug"
SAMPLE = "今天天气很好，我们下午三点去公园散步。"


class Runner:
    def __init__(self, args):
        self.args = args
        self.output = Path(tempfile.mkdtemp(prefix="voice-acoustic-"))
        self.commands = (self.output / "commands.jsonl").open("w")
        self.lines = []
        self.dialog = False
        self.logcat = None
        self.results = []
        print(f"Artifacts: {self.output}", flush=True)

    def run(self, *command, timeout=30):
        start = time.time()
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        self.commands.write(json.dumps(dict(time=start, command=command,
            stdout=result.stdout, stderr=result.stderr, returncode=result.returncode),
            ensure_ascii=False) + "\n")
        self.commands.flush()
        if result.returncode:
            raise RuntimeError(f"Command failed: {command}: {result.stderr or result.stdout}")
        return result.stdout

    def adb(self, *args):
        return self.run("adb", "-s", self.args.serial, *args)

    def ui(self):
        self.adb("shell", "uiautomator", "dump", "/sdcard/voice-acoustic-window.xml")
        return ET.fromstring(self.adb("shell", "cat", "/sdcard/voice-acoustic-window.xml"))

    def tap(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    @staticmethod
    def named(root, name):
        return next((n for n in root.iter("node") if n.get("text") == name), None)

    @staticmethod
    def editor(root):
        fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
        if len(fields) != 1:
            raise RuntimeError("Expected exactly one test editor")
        return fields[0]

    def prepare(self):
        if self.adb("get-state").strip() != "device":
            raise RuntimeError("ADB device is not ready")
        if "2200x2480" not in self.adb("shell", "wm", "size"):
            raise RuntimeError("This fixture requires the verified 2200x2480 unfolded layout")
        ime = self.adb("shell", "settings", "get", "secure", "default_input_method").strip()
        if not ime.startswith(PACKAGE + "/"):
            raise RuntimeError("The voice debug keyboard must be the active input method")
        self.adb("shell", "am", "start", "-n", PACKAGE +
            "/org.fcitx.fcitx5.android.ui.main.MainActivity")
        for _ in range(5):
            root = self.ui()
            keyboard = self.named(root, "虚拟键盘")
            hotwords = self.named(root, "用户热词")
            if hotwords is not None:
                break
            if keyboard is not None:
                if self.named(root, "全局选项") is not None:
                    self.tap(keyboard)
                break
            self.adb("shell", "input", "keyevent", "4")
        else:
            raise RuntimeError("Could not find keyboard settings")
        for _ in range(6):
            root = self.ui()
            hotwords = self.named(root, "用户热词")
            if hotwords is not None:
                self.tap(hotwords)
                self.dialog = True
                break
            self.adb("shell", "input", "swipe", "1300", "2150", "1300", "450", "450")
        else:
            raise RuntimeError("Could not find the hotword editor")
        root = self.ui()
        field = self.editor(root)
        if field.get("text"):
            raise RuntimeError("Hotwords must be empty for this controlled fixture; existing text was not changed")
        if self.named(root, "取消") is None:
            raise RuntimeError("Editor has no Cancel action")
        self.tap(field)
        if "mInputShown=true" not in self.adb("shell", "dumpsys", "input_method"):
            raise RuntimeError("Keyboard is not visible")
        pid = self.adb("shell", "pidof", PACKAGE).strip()
        if not pid.isdigit():
            raise RuntimeError("Expected one application process")
        command = ["adb", "-s", self.args.serial, "logcat", "-v", "epoch", "-T", "1", "--pid=" + pid]
        self.commands.write(json.dumps({"command": command, "stream": "device.log"}) + "\n")
        self.commands.flush()
        self.logcat = subprocess.Popen(command, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.reader = threading.Thread(target=self.read_logs, daemon=True)
        self.reader.start()
        self.audio = self.output / "sample.aiff"
        self.run("say", "-v", "Tingting", "-r", "180", "-o", str(self.audio), self.args.text)
        self.run("afinfo", str(self.audio))

    def read_logs(self):
        with (self.output / "device.log").open("w") as output:
            for line in self.logcat.stdout:
                output.write(line)
                output.flush()
                self.lines.append(line)

    @staticmethod
    def stamp(line):
        return float(line.split()[0])

    @staticmethod
    def comparable(text):
        # The fixture accepts spoken time written as either Chinese or Arabic numerals.
        return "".join(c for c in text.replace("三点", "3点")
            if not c.isspace() and not unicodedata.category(c).startswith("P"))

    def trial(self, index):
        if index > 1:
            root = self.ui()
            if self.editor(root).get("text") != self.results[-1]["text"]:
                raise RuntimeError("Editor changed outside the test; refusing to clear it")
            self.adb("shell", "input", "keycombination", "113", "29")
            self.adb("shell", "input", "keyevent", "67")
            if self.editor(self.ui()).get("text"):
                raise RuntimeError("Could not clear the previous test result")
        offset = len(self.lines)
        print(f"Trial {index}: recording and playing through the current Mac output", flush=True)
        try:
            self.adb("shell", "input", "motionevent", "DOWN", "1200", "2350")
            time.sleep(0.8)
            self.run("afplay", str(self.audio))
        finally:
            self.adb("shell", "input", "motionevent", "UP", "1200", "2350")
        deadline = time.monotonic() + self.args.timeout
        preview_screenshot = None
        preview_before_commit = False
        while time.monotonic() < deadline:
            lines = self.lines[offset:]
            if any("Voice input failed" in s or "MNN response error" in s for s in lines):
                raise RuntimeError("Voice recognition failed; see device.log")
            queued = [s for s in lines if "Voice segment queued:" in s]
            commits = [s for s in lines if "Voice segment committed:" in s]
            stops = [s for s in lines if "AudioRecord: stop(" in s and "mActive:1" in s]
            if preview_screenshot is None and any("Voice segment first preview:" in s for s in lines):
                self.adb("shell", "screencap", "-p", "/sdcard/voice-acoustic-preview.png")
                preview_before_commit = not any("Voice segment committed:" in s for s in self.lines[offset:])
                preview_screenshot = str(self.output / f"preview-{index}.png")
                self.adb("pull", "/sdcard/voice-acoustic-preview.png", preview_screenshot)
                continue
            if queued and len(commits) == len(queued) and stops:
                break
            if self.logcat.poll() is not None:
                raise RuntimeError("Logcat exited during the test")
            time.sleep(0.1)
        else:
            raise TimeoutError("No complete voice result before the deadline; see device.log")
        root = self.ui()
        text = self.editor(root).get("text", "")
        committed_text = "".join(s.split(" text=", 1)[1].strip() for s in commits)
        requests = [s for s in lines if "MNN request:" in s]
        responses = [s for s in lines if "MNN response:" in s]
        previews = [s for s in lines if "Voice segment first preview:" in s]
        result = dict(trial=index, text=text,
            ui_matches_commit=text == committed_text,
            content_match=self.comparable(text) == self.comparable(self.args.text),
            preview_screenshot=preview_screenshot,
            preview_before_commit=preview_before_commit,
            stop_to_commit_s=round(self.stamp(commits[-1]) - self.stamp(stops[-1]), 3),
            segment_count=len(queued),
            stop_to_first_preview_s=round(self.stamp(previews[0]) - self.stamp(stops[-1]), 3) if previews else None,
            first_preview_to_commit_s=round(self.stamp(commits[-1]) - self.stamp(previews[0]), 3) if previews else None,
            request_to_response_s=[round(self.stamp(b) - self.stamp(a), 3)
                for a, b in zip(requests, responses)])
        self.results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)

    def close(self):
        if self.dialog:
            root = self.ui()
            cancel = self.named(root, "取消")
            if cancel is None:
                raise RuntimeError("Could not cancel the test editor; no Save action was attempted")
            self.tap(cancel)
            if any(n.get("class") == "android.widget.EditText" for n in self.ui().iter("node")):
                raise RuntimeError("Test editor did not close")

    def execute(self):
        error = None
        try:
            self.prepare()
            for index in range(1, self.args.trials + 1):
                self.trial(index)
            if any(not r["ui_matches_commit"] or not r["content_match"] for r in self.results):
                error = "Transcription verification failed; all completed trials are retained"
            if self.args.require_preview and any(not r["preview_before_commit"] or not r["preview_screenshot"]
                    for r in self.results):
                error = "No preview screenshot was captured before commit"
        except Exception as exc:
            error = str(exc)
        finally:
            try:
                self.close()
            except Exception as exc:
                error = f"{error or ''} Cleanup: {exc}"
            if self.logcat:
                self.logcat.terminate()
                self.logcat.wait(timeout=5)
                self.reader.join(timeout=5)
            report = dict(expected=self.args.text, results=self.results, error=error,
                measurement="Same-device AudioRecord stop to commit log; not acoustic end to pixels",
                output_device="Existing macOS default output; not changed by this runner")
            (self.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
            self.commands.close()
        print(f"Report: {self.output / 'report.json'}", flush=True)
        if error:
            raise SystemExit(error)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="10AG3F087Y005YN")
    parser.add_argument("--trials", type=int, choices=range(1, 11), default=2)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--text", default=SAMPLE)
    parser.add_argument("--require-preview", action="store_true")
    Runner(parser.parse_args()).execute()
