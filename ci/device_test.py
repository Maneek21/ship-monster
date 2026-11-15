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
                "desc": n.get("content-desc", ""),
                "id": n.get("resource-id", "").split("/")[-1],
                "cls": n.get("class", ""),
                "checked": n.get("checked") == "true",
                "pkg": n.get("package", ""),
                "b": [int(v) for v in b],
            })
        return nodes
    return []


def find(nodes, id=None, text=None, contains=None, cls=None, desc=None, desc_starts=None):
    for n in nodes:
        if desc is not None and n["desc"].lower() != desc.lower():
            continue
        if desc_starts is not None and not n["desc"].lower().startswith(desc_starts.lower()):
            continue
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


def desc_of(prefix):
    """The rest of a content description that starts with prefix, or None."""
    n = find(dump(), desc_starts=prefix)
    return n["desc"][len(prefix):].strip() if n else None


def state_is(word):
    return (desc_of("state ") or "").lower() == word.lower()


def tab(name):
    n = find(dump(), desc="tab " + name)
    if n:
        tap(n)
    return n is not None


def num(prefix):
    v = desc_of(prefix)
    if v is None:
        # the header chips sit at the top of every tab
        scroll(False)
        scroll(False)
        v = desc_of(prefix)
    return int(v) if v and v.lstrip("-").isdigit() else -1


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


def game_prefs():
    raw = prefs("game")
    m = re.search(r'name="state">(.*?)</string>', raw, re.S)
    return (m.group(1).replace("&quot;", '"') if m else raw)


def open_settings():
    home()
    tab("Setup")
    time.sleep(1.5)


def lab(id, times=1):
    """Press a test lab button on the setup screen."""
    for _ in range(times):
        seek_tap(id, id=id)


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

    # 1. Onboarding
    home()
    check("first launch opens onboarding", find(dump(), contains="Step 1 of 5") is not None)
    shot("01_onboarding_1")
    seek_tap("next", text="Next")
    shot("02_onboarding_2")
    seek_tap("next", text="Next")
    shot("03_onboarding_3")
    seek_tap("next", text="Next")
    field = find(dump(), cls="android.widget.EditText")
    check("onboarding asks for a GitHub username", field is not None)
    if field:
        tap(field)
        sh("input text bad..name")
        seek_tap("continue", text="Continue")
        check("a bad username is refused", find(dump(), contains="Step 4 of 5") is not None)
        field = find(dump(), cls="android.widget.EditText")
        if field:
            tap(field)
            sh("input keyevent 123")
            sh("input keyevent --longpress " + " ".join(["67"] * 20))
            sh(f"input text {GITHUB_USER}")
        shot("04_onboarding_username")
        seek_tap("continue", text="Continue")
    check("username step leads to the starter choice", find(dump(), contains="Step 5 of 5") is not None)
    seek_tap("starter", desc="starter Puddlewump")
    check("a starter can be selected", find(dump(), desc="starter Puddlewump selected") is not None)
    shot("05_onboarding_starter")
    seek_tap("start", text="Start with Puddlewump")

    # 2. First sync with a real username, no token
    status = wait_for(lambda: (lambda n: n["text"] if n and "Checking" not in n["text"] else None)(find(dump(), desc="sync status")), 45)
    note("first sync message", status)
    check("first sync with a username reaches GitHub", bool(status) and "Could not" not in status and "GitHub answered" not in status, status)
    check("onboarding ends on the den", find(dump(), desc_starts="den ") is not None)
    shot("06_den_after_first_sync")
    note("den after first sync", desc_of("den "))

    # From here on the phone is offline. That keeps real pushes made during the run from feeding
    # the horde mid-test, and proves the lock does not depend on the network.
    sh("cmd connectivity airplane-mode enable")
    time.sleep(2)

    # 3. Setup: no rest days during the test, so skipped days always count
    open_settings()
    for d in ("Su", "Sa"):
        n = seek(desc=f"rest {d} on")
        if n:
            tap(n)
    check("rest days can be switched off", seek(desc="rest Su off") is not None and find(dump(), desc="rest Sa off") is not None)
    shot("07_setup_rest_days")

    # 4. Feed the horde with the test lab
    lab("btn_commit", 3)
    lab("btn_merge", 2)
    seek_tap("add gems", text="Add 5 gems")
    seek_tap("add growth", text="Add 100 growth to the party")
    home()
    party = desc_of("den ")
    check("the starter hatched and leads the party", party is not None and "Puddlewump" in party, party)
    check("pushes filled the pantry", num("chow ") >= 20, num("chow "))
    check("merged pull requests paid gems", num("gems ") >= 7, num("gems "))
    stage = desc_of("monster ")
    check("growth evolved the monster", stage is not None and int(stage.split("stage")[-1]) >= 3, stage)
    before = (find(dump(), desc="speech") or {}).get("text")
    den = find(dump(), desc_starts="den ")
    if den:
        tap(den)
    after = (find(dump(), desc="speech") or {}).get("text")
    check("poking the monster makes it speak", bool(after) and after != before, f"{before!r} -> {after!r}")
    shot("08_den_poked")
    chow = num("chow ")
    seek_tap("treat", desc="treat")
    check("a treat costs two chow", num("chow ") == chow - 2, num("chow "))
    check("three treats a day", seek(contains="Treat (2 left)") is not None)

    # 5. Nest: buy and crack eggs
    tab("Nest")
    gems = num("gems ")
    seek_tap("buy egg", contains="Buy an egg")
    check("an egg costs a gem", num("gems ") == gems - 1, num("gems "))
    egg = seek(desc="egg 0")
    check("the egg waits in the nest", egg is not None)
    shot("09_nest_egg")
    for _ in range(3):
        egg = seek(desc="egg 0")
        if egg:
            tap(egg)
    hatched = wait_for(lambda: desc_of("hatched "), 5)
    check("three taps crack the egg", hatched is not None, hatched)
    shot("10_nest_hatched")
    for _ in range(3):
        seek_tap("buy egg", contains="Buy an egg")
        for _ in range(3):
            egg = seek(desc="egg 0")
            if egg:
                tap(egg)
    gems = num("gems ")
    seek_tap("feast", contains="Throw a feast")
    check("a feast costs two gems", num("gems ") == gems - 2, num("gems "))
    seek(contains="What is in an egg")
    shot("11_nest_odds")

    # 6. Horde and a monster's page
    tab("Horde")
    title = find(dump(), contains="Your monsters")
    count = int(re.findall(r"\d+", title["text"])[0]) if title else 0
    check("hatched monsters are in the horde", count >= 2, title["text"] if title else None)
    shot("12_horde")
    seek(contains="Monsterdex")
    scroll(True)
    shot("13_monsterdex")
    scroll(False)
    scroll(False)
    opened = seek_tap("monster tile", desc_starts="open ")
    check("a monster page opens", opened and seek(text="Evolution") is not None)
    shot("14_monster_page")
    scroll(False)
    scroll(False)
    shot("15_monster_page_top")

    # 7. Log
    tab("Log")
    check("the log lists what happened", find(dump(), contains="What happened") is not None or seek(contains="What happened") is not None)
    check("pushes are in the log", seek(contains="Pushed to lab") is not None)
    shot("16_log")

    # 8. Arm the lock
    sh(f"appops set {PKG} GET_USAGE_STATS allow")
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    open_settings()
    seek_tap("choose apps", id="btn_apps")
    time.sleep(1)
    nodes = dump()
    items = [n for n in nodes if n["cls"] == "android.widget.CheckedTextView"]
    check("app picker lists apps", len(items) > 0, [n["text"] for n in items][:12])
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
    shot("17_setup_lock_armed")

    # 9. Restless, then feral
    lab("btn_skip24")
    home()
    check("a day without shipping makes the horde restless", state_is("restless"), desc_of("state "))
    shot("18_den_restless")
    open_settings()
    lab("btn_skip24")
    home()
    check("two days without shipping make it feral", state_is("feral"), desc_of("state "))
    check("going feral resets the streak", num("streak ") == 0, num("streak "))
    shot("19_den_feral")
    time.sleep(8)
    check("a sync that cannot reach GitHub leaves the horde feral", state_is("feral"), desc_of("state "))

    # 10. The lock
    target = blocked[0] if blocked else None
    if target:
        sh(f"pm grant {target} android.permission.POST_NOTIFICATIONS")
        sh("input keyevent 3")
        time.sleep(1)
        sh(f"monkey -p {target} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        lock_text = wait_for(lambda: text_of("lock_text"), 15)
        check("lock screen covers the locked app", lock_text is not None and "No ship in" in lock_text, lock_text)
        note("lock countdown", text_of("lock_count"))
        shot("20_lock_overlay")

        n = find(dump(), id="lock_check")
        if n:
            tap(n)
            lock_note = wait_for(lambda: (lambda t: t if t and "Checking" not in t else None)(text_of("lock_note")), 30)
            check("check-now says it is offline and stays locked", bool(lock_note) and "No connection" in lock_note and text_of("lock_text") is not None, lock_note)

        n = find(dump(), id="lock_home")
        if n:
            tap(n)
        time.sleep(3)
        check("back-to-home removes the lock screen", text_of("lock_text") is None)

        sh("am start -a android.settings.SETTINGS >/dev/null 2>&1")
        time.sleep(4)
        check("an app that is not on the list stays open", text_of("lock_text") is None)
        sh("input keyevent 3")
        time.sleep(1)

        sh(f"monkey -p {target} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        again = wait_for(lambda: text_of("lock_text"), 15)
        check("reopening the locked app locks it again", again is not None)

        # a truce costs a gem and opens the app
        n = find(dump(), id="lock_truce")
        check("the lock screen offers a truce", n is not None and "1 gem" in n["text"], n["text"] if n else None)
        if n:
            tap(n)
        time.sleep(3)
        check("a truce removes the lock screen", text_of("lock_text") is None)
        front = sh("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'").strip()
        check("the app that was locked is usable during a truce", target in front, front[-90:])
        home()
        check("the den shows the truce", seek(contains="Truce: apps open") is not None)
        shot("21_den_truce")

        # a day later the truce is over: locked again, no second truce, surrender needs two taps
        open_settings()
        lab("btn_skip24")
        sh("input keyevent 3")
        time.sleep(2)
        sh(f"monkey -p {target} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        third = wait_for(lambda: text_of("lock_text"), 20)
        check("the lock returns when the truce ends", third is not None)
        check("only one truce per feral spell", find(dump(), id="lock_truce") is None)
        n = find(dump(), id="lock_mercy")
        if n:
            tap(n)
            armed = text_of("lock_mercy")
            check("surrender asks for a second tap", armed is not None and "Tap again" in armed, armed)
            shot("22_surrender_armed")
            time.sleep(6)
            calm = text_of("lock_mercy")
            check("a single tap does not surrender", calm == "Surrender" and text_of("lock_text") is not None, calm)
            tap(n)
            tap(n)
        time.sleep(3)
        check("surrender removes the lock screen", text_of("lock_text") is None)
    else:
        check("a locked app was available to test", False)

    # 11. The fall, and what is left
    tab("Log") if home() is None else None
    check("the fall is in the hall of fame", seek(contains="The horde fell. Led by") is not None)
    shot("23_log_hall")
    titles = sorted(set(re.findall(r"android\.title=String \(([^)]*)\)", sh("dumpsys notification --noredact"))))
    note("notification titles", titles)
    check("restless and feral were announced", any("restless" in t for t in titles) and any("feral" in t for t in titles), titles)
    home()
    if seek(contains="The horde fell. Start again.") is not None:
        shot("24_den_fallen")
        seek_tap("starter", desc="starter Mossback")
        seek_tap("start", text="Start with Mossback")
        open_settings()
        lab("btn_commit")
        home()
    else:
        note("after the fall", "a den monster stepped up to lead")
    party = desc_of("den ")
    check("the game goes on after a fall", party is not None and party.startswith("calm:") and len(party) > 8, party)
    shot("25_den_after_fall")
    tab("Horde")
    shot("26_horde_after_fall")

    # 12. Share image
    if seek_tap("monster tile", desc_starts="open "):
        seek_tap("share", text="Share")
        time.sleep(3)
        png = adb("exec-out", "run-as", PKG, "cat", f"/data/data/{PKG}/cache/share/ship-monster.png", binary=True)
        ok = png[:4] == b"\x89PNG"
        check("share image was written", ok, f"{len(png)} bytes")
        if ok:
            p = os.path.join(OUT, "26_share_card.png")
            with open(p, "wb") as f:
                f.write(png)
            shots.append(("27_share_card", p))
        sh("input keyevent 4")
        time.sleep(1)

    # 13. Widget through the launcher's pin request
    home()
    if seek_tap("add widget", text="Add the widget"):
        time.sleep(2)
        nodes = dump()
        btn = find(nodes, contains="Add to home") or find(nodes, contains="Add automatically") or find(nodes, text="Add")
        if btn:
            tap(btn)
        time.sleep(2)
        sh("input keyevent 3")
        time.sleep(2)
        shot("28_launcher_with_widget")
        bound = PKG in sh("dumpsys appwidget")
        w = find(dump(), id="pet_say")
        check("widget is on the home screen and the monster speaks", bound and w is not None and len(w["text"]) > 3, f"bound={bound} text={w['text'] if w else None}")

    # 14. The saved game is sane
    state = game_prefs()
    check("the game is saved", '"monsters"' in state and '"hall"' in state, state[:300])

    # 15. Crashes so far
    c = crashes()
    check("no crashes", not c, c[-1500:])

    # 16. Reboot: sync job and guard must come back on their own
    note("app log before reboot", app_log().strip().splitlines()[-14:])
    sh("cmd connectivity airplane-mode disable")
    sh("cmd appops write-settings")
    adb("reboot")
    time.sleep(10)
    adb("wait-for-device", timeout=240)
    wait_for(lambda: sh("getprop sys.boot_completed").strip() == "1", 240, 3)
    ok = wait_for(lambda: all(service_running()), 150, 5)
    run, fg = service_running()
    check("guard service restarts after a reboot", run and fg, f"running={run} foreground={fg}")
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
