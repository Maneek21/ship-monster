#!/usr/bin/env python3
"""
Drives the debug APK on an Android emulator and checks that the main flows work.

Run by .github/workflows/android.yml inside the emulator job:
    python3 ci/device_test.py path/to/app-debug.apk

It prints PASS or FAIL per check, then prints each screenshot as base64 between
@@SHOT markers so the images can be recovered from the build log.
"""
import base64
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "dev.shipmonster.app"
GITHUB_USER = os.environ.get("TEST_GITHUB_USER", "Maneek21")
OUT = os.environ.get("SHOTS_DIR", "/tmp/shots")
os.makedirs(OUT, exist_ok=True)

results = []
shots = []


def adb(*args, timeout=90, binary=False):
    try:
        p = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else ""
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def log(msg):
    print(msg, flush=True)


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    log(("PASS  " if ok else "FAIL  ") + name + (("  | " + str(detail)) if detail else ""))


def note(name, detail):
    log("NOTE  " + name + "  | " + str(detail))


def shot(name):
    data = adb("exec-out", "screencap", "-p", binary=True)
    if data[:4] == b"\x89PNG":
        path = os.path.join(OUT, name + ".png")
        with open(path, "wb") as f:
            f.write(data)
        shots.append((name, path))
    else:
        log("NOTE  screenshot failed: " + name)


def dump():
    """Visible UI nodes of the focused window."""
    for _ in range(4):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        raw = adb("exec-out", "cat", "/sdcard/ui.xml")
        try:
            root = ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(1)
            continue
        nodes = []
        for n in root.iter("node"):
            b = re.findall(r"-?\d+", n.get("bounds", ""))
            if len(b) != 4:
                continue
            nodes.append({
                "text": n.get("text", ""),
                "id": n.get("resource-id", "").split("/")[-1],
                "cls": n.get("class", ""),
                "checked": n.get("checked") == "true",
                "pkg": n.get("package", ""),
                "b": [int(v) for v in b],
            })
        return nodes
    return []


def find(nodes, id=None, text=None, contains=None, cls=None):
    for n in nodes:
        if id is not None and n["id"] != id:
            continue
        if text is not None and n["text"].lower() != text.lower():
            continue
        if contains is not None and contains.lower() not in n["text"].lower():
            continue
        if cls is not None and n["cls"] != cls:
            continue
        return n
    return None


def tap(n):
    x = (n["b"][0] + n["b"][2]) // 2
    y = (n["b"][1] + n["b"][3]) // 2
    sh(f"input tap {x} {y}")
    time.sleep(1.2)


def screen_size():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


W, H = 1080, 2400


def scroll(down=True):
    x = W // 2
    a, b = (int(H * 0.72), int(H * 0.32)) if down else (int(H * 0.32), int(H * 0.72))
    sh(f"input swipe {x} {a} {x} {b} 350")
    time.sleep(0.8)


def seek(tries=9, **kw):
    """Find a node, scrolling down and then back up if needed."""
    n = find(dump(), **kw)
    if n:
        return n
    for _ in range(tries):
        scroll(True)
        n = find(dump(), **kw)
        if n:
            return n
    for _ in range(tries):
        scroll(False)
        n = find(dump(), **kw)
        if n:
            return n
    return None


def seek_tap(name, **kw):
    n = seek(**kw)
    if not n:
        check("find " + name, False, str(kw))
        return False
    # Nudge it clear of the screen edges before tapping.
    if n["b"][3] > H * 0.9:
        scroll(True)
        n = find(dump(), **kw) or n
    tap(n)
    return True


def text_of(id, wait=0):
    end = time.time() + wait
    while True:
        n = find(dump(), id=id)
        if n or time.time() >= end:
            return n["text"] if n else None
        time.sleep(1)


def wait_for(fn, seconds, every=1.0):
    end = time.time() + seconds
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(every)
    return fn()


def home():
    # clear-top closes the setup screen if it is open above the home screen
    sh(f"am start -W --activity-clear-top -n {PKG}/.MainActivity")
    time.sleep(2)


def hide_keyboard():
    if "mInputShown=true" in sh("dumpsys input_method"):
        sh("input keyevent 4")
        time.sleep(1)


def app_log():
    return adb("logcat", "-d", "-s", "ShipMonster:V")


def pet_prefs():
    raw = prefs("pet")
    m = re.search(r'name="state">(.*?)</string>', raw, re.S)
    return (m.group(1).replace("&quot;", '"') if m else raw)[:700]


def open_settings():
    home()
    seek_tap("settings button", id="btn_settings")
    time.sleep(1.5)


def prefs(name):
    return sh(f"run-as {PKG} cat /data/data/{PKG}/shared_prefs/{name}.xml")


def crashes():
    out = adb("logcat", "-d", "-b", "crash")
    return out if PKG in out or "FATAL EXCEPTION" in out else ""


def service_running():
    out = sh(f"dumpsys activity services {PKG}")
    return "LockService" in out, ("isForeground=true" in out)


def main():
    global W, H
    apk = sys.argv[1]
    api = sh("getprop ro.build.version.sdk").strip()
    log(f"== device: API {api}, {sh('getprop ro.product.model').strip()}")
    W, H = screen_size()
    sh("settings put secure show_ime_with_hard_keyboard 0")
    sh("settings put global heads_up_notifications_enabled 0")
    adb("logcat", "-c")
    adb("logcat", "-b", "crash", "-c")

    out = adb("install", "-r", apk, timeout=180)
    check("install", "Success" in out, out.strip()[-80:])
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")

    # 1. First launch
    home()
    nodes = dump()
    check("app opens on the home screen", find(nodes, id="main_name") is not None)
    check("new pet is an egg", "still an egg" in (text_of("main_name") or ""), text_of("main_name"))
    check("setup list offers Connect GitHub", find(nodes, text="Connect GitHub") is not None)
    shot("01_home_egg")

    # 2. Setup screen and real GitHub sync with a username only
    open_settings()
    check("setup screen opens", find(dump(), id="et_username") is not None)
    shot("02_setup_top")
    if seek_tap("username field", id="et_username"):
        sh(f"input text {GITHUB_USER}")
        time.sleep(0.5)
    seek_tap("save and sync", id="btn_save_github")
    time.sleep(1)
    check("keyboard is put away after saving", "mInputShown=true" not in sh("dumpsys input_method"))
    hide_keyboard()
    state = wait_for(lambda: (lambda t: t if t and "Checking" not in t else None)(text_of("tv_github_state")), 40)
    check("username saved", state is not None and GITHUB_USER in (state or ""), state)
    home()
    sync_msg = text_of("main_message", wait=5)
    note("first sync message (no token)", sync_msg)
    note("app log after first sync", app_log().strip().splitlines()[-4:])
    note("pet state after first sync", pet_prefs())
    note("pet after first sync", f"{text_of('main_name')} / {text_of('main_state')} / {text_of('main_meta')}")
    check("first sync returned a real answer", bool(sync_msg) and "unexpected" not in (sync_msg or "").lower(), sync_msg)
    shot("03_home_after_sync")

    # 3. Make sure there is a living pet, using the test lab if sync did not hatch it
    if "egg" in (text_of("main_name") or ""):
        open_settings()
        seek_tap("fake commit", id="btn_commit")
        home()
    check("pet is alive and happy", (text_of("main_state") or "").lower().startswith(("happy", "sick")), text_of("main_state"))
    tap(find(dump(), id="main_pet") or {"b": [0, 0, 2, 2]})
    shot("04_home_happy")

    # 4. Arm the lock
    sh(f"appops set {PKG} GET_USAGE_STATS allow")
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    open_settings()
    seek_tap("choose apps", id="btn_apps")
    time.sleep(1)
    nodes = dump()
    items = [n for n in nodes if n["cls"] == "android.widget.CheckedTextView"]
    check("app picker lists apps", len(items) > 0, [n["text"] for n in items][:12])
    shot("05_app_picker")
    if items and not any(n["checked"] for n in items):
        tap(items[0])
    save = find(dump(), text="Save")
    if save:
        tap(save)
    time.sleep(1.5)
    blocked = re.findall(r"<string>([\w.]+)</string>", prefs("settings"))
    check("picked apps are saved", len(blocked) > 0, blocked)
    seek_tap("lock switch", id="sw_lock")
    time.sleep(2)
    lock_state = text_of("tv_lock_state")
    check("setup screen says the lock is armed and running", lock_state == "Armed and running.", lock_state)
    run, fg = service_running()
    check("guard service is running in the foreground", run and fg, f"running={run} foreground={fg}")
    seek(id="sw_lock")
    shot("06_setup_lock_armed")

    # 5. Starve the pet into a monster with the test lab
    for _ in range(2):
        seek_tap("skip 1 day", id="btn_skip24")
    home()
    st = text_of("main_state")
    check("pet turns monster after two skipped days", st == "Monster", st)
    shot("07_home_monster")
    # Let the automatic sync on opening the home screen finish. It must not feed the pet again.
    time.sleep(8)
    note("sync message while monster", text_of("main_message"))
    check("an automatic sync with nothing new leaves the monster a monster", text_of("main_state") == "Monster", text_of("main_state"))

    # 6. Open a locked app: the overlay must appear
    target = blocked[0] if blocked else None
    if target:
        # Keep the locked app's own first-run prompts out of the way.
        sh(f"pm grant {target} android.permission.POST_NOTIFICATIONS")
        sh("input keyevent 3")
        time.sleep(1)
        sh(f"monkey -p {target} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        lock_text = wait_for(lambda: text_of("lock_text"), 15)
        check("lock screen covers the locked app", lock_text is not None and "is closed" in lock_text, lock_text)
        note("lock countdown", text_of("lock_count"))
        shot("08_lock_overlay")

        # check now: no new ship, so it must stay locked and say so
        n = find(dump(), id="lock_check")
        if n:
            tap(n)
            lock_note = wait_for(lambda: (lambda t: t if t and "Checking" not in t else None)(text_of("lock_note")), 30)
            check("check-now reports back and stays locked", bool(lock_note) and text_of("lock_text") is not None, lock_note)

        # back to home hides it
        n = find(dump(), id="lock_home")
        if n:
            tap(n)
        time.sleep(3)
        check("back-to-home removes the lock screen", text_of("lock_text") is None)
        shot("09_after_back_home")

        # unlocked apps stay usable while the pet is a monster
        sh("am start -a android.settings.SETTINGS >/dev/null 2>&1")
        time.sleep(4)
        check("an app that is not on the list stays open", text_of("lock_text") is None)
        sh("input keyevent 3")
        time.sleep(1)

        # reopening the locked app locks again, then mercy unlock
        sh(f"monkey -p {target} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        again = wait_for(lambda: text_of("lock_text"), 15)
        check("reopening the locked app locks it again", again is not None)
        n = find(dump(), id="lock_mercy")
        if n:
            # One tap only arms it. Left alone, it must disarm and stay locked.
            tap(n)
            armed = text_of("lock_mercy")
            check("mercy unlock asks for a second tap", armed is not None and "Tap again" in armed, armed)
            shot("10_mercy_armed")
            time.sleep(6)
            calm = text_of("lock_mercy")
            check("a single tap does not unlock", calm == "Mercy unlock" and text_of("lock_text") is not None, calm)
            # Two taps in a row confirm it.
            tap(n)
            tap(n)
        time.sleep(3)
        check("mercy unlock removes the lock screen", text_of("lock_text") is None)
        front = sh("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'").strip()
        check("the app that was locked is now usable", target in front, front[-90:])
    else:
        check("a locked app was available to test", False)

    # 7. Death, graveyard, notifications
    home()
    name = text_of("main_name")
    check("pet is dead after mercy unlock", (name or "").startswith("R.I.P."), name)
    grave = seek(contains="Mercy-unlocked so its owner could scroll")
    check("graveyard shows the epitaph", grave is not None)
    shot("11_home_dead")
    titles = sorted(set(re.findall(r"android\.title=String \(([^)]*)\)", sh("dumpsys notification --noredact"))))
    note("notification titles", titles)
    check("monster and death were announced", any("monster" in t for t in titles) or any("died" in t for t in titles), titles)

    # 8. Share image
    seek_tap("share", id="btn_share")
    time.sleep(3)
    shot("12_share_sheet")
    png = adb("exec-out", "run-as", PKG, "cat", f"/data/data/{PKG}/cache/share/ship-monster.png", binary=True)
    ok = png[:4] == b"\x89PNG"
    check("share image was written", ok, f"{len(png)} bytes")
    if ok:
        p = os.path.join(OUT, "13_share_card.png")
        with open(p, "wb") as f:
            f.write(png)
        shots.append(("13_share_card", p))
    sh("input keyevent 4")
    time.sleep(1)

    # 9. New egg
    home()
    seek_tap("hatch", id="btn_hatch")
    check("hatching gives a new egg", "still an egg" in (text_of("main_name") or ""), text_of("main_name"))

    # 10. Widget through the launcher's pin request
    if seek_tap("add widget", text="Add the home screen widget"):
        time.sleep(2)
        shot("14_widget_pin_dialog")
        nodes = dump()
        btn = find(nodes, contains="Add to home") or find(nodes, contains="Add automatically") or find(nodes, text="Add")
        if btn:
            tap(btn)
        time.sleep(2)
        sh("input keyevent 3")
        time.sleep(2)
        shot("15_launcher_with_widget")
        bound = PKG in sh("dumpsys appwidget")
        w = find(dump(), id="pet_name")
        check("widget is on the home screen", bound and w is not None, f"bound={bound} text={w['text'] if w else None}")

    # 11. Crashes so far
    c = crashes()
    check("no crashes", not c, c[-1500:])

    # 12. Reboot: sync job and guard must come back on their own
    note("lock settings before reboot", re.findall(r'name="(lock_enabled)" value="(\w+)"', prefs("settings")))
    adb("reboot")
    time.sleep(10)
    adb("wait-for-device", timeout=240)
    wait_for(lambda: sh("getprop sys.boot_completed").strip() == "1", 240, 3)
    ok = wait_for(lambda: all(service_running()), 150, 5)
    run, fg = service_running()
    check("guard service restarts after a reboot", run and fg, f"running={run} foreground={fg}")
    note("usage access after reboot", sh(f"appops get {PKG} GET_USAGE_STATS").strip())
    note("overlay permission after reboot", sh(f"appops get {PKG} SYSTEM_ALERT_WINDOW").strip())
    note("app log after reboot", app_log().strip().splitlines()[-8:])
    job = sh(f"dumpsys jobscheduler | grep -c {PKG}/.SyncJobService").strip()
    check("background sync job survives a reboot", job.isdigit() and int(job) > 0, job)
    c = crashes()
    check("no crashes after reboot", not c, c[-1500:])

    # Report
    failed = [r for r in results if not r[1]]
    log(f"\n== {len(results) - len(failed)} passed, {len(failed)} failed (API {api})")
    for name, ok, detail in failed:
        log(f"   FAILED: {name} | {detail}")
    print_shots()
    if failed:
        log("== logcat tail for our process")
        log(adb("logcat", "-d", "-t", "400")[-12000:])
    sys.exit(1 if failed else 0)


def print_shots():
    try:
        from PIL import Image
    except Exception:
        log("NOTE  Pillow missing, screenshots not printed")
        return
    for name, path in shots:
        try:
            im = Image.open(path).convert("RGB")
            w = 540
            im = im.resize((w, int(im.height * w / im.width)))
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=72)
            b64 = base64.b64encode(buf.getvalue()).decode()
            log(f"@@SHOT {name} BEGIN")
            for i in range(0, len(b64), 4000):
                log(b64[i:i + 4000])
            log("@@SHOT END")
        except Exception as e:
            log(f"NOTE  could not print {name}: {e}")


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        import traceback
        traceback.print_exc()
        log("FAIL  test script crashed: " + repr(e))
        print_shots()
        sys.exit(1)
