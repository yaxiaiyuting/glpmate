#!/usr/bin/env python3
"""不依赖第三方库的浏览器自检：真加载页面，抓控制台/异常，取状态，截图。

用法：
  python3 tools/browsercheck.py [url] [screenshot.png] [wait_seconds]

为什么需要它：`firefox --screenshot` 这类一次性截图会在首次绘制后就退出，
定时器、异步请求、模块加载都来不及完成，看到的"空白页"是假象；
手机上又没有控制台。所以这里直接用 CDP 连一个真实的 Chrome 内核。

自动查找浏览器：$GLPMATE_CHROME，或 puppeteer 缓存里的 chrome-headless-shell，
或系统 chrome/chromium。都找不到时打印提示并以退出码 2 结束。
"""

from __future__ import annotations

import base64
import glob
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

FALLBACK_PORTS = (9444, 9445, 9446)


# --------------------------------------------------------------------- 浏览器
def find_browser() -> list[str] | None:
    env = os.environ.get("GLPMATE_CHROME")
    if env and Path(env).exists():
        return [env]
    patterns = [
        str(Path.home() / ".cache/puppeteer/chrome-headless-shell/*/chrome-headless-shell-*/chrome-headless-shell"),
        str(Path.home() / ".cache/puppeteer/chrome/*/chrome-*/chrome"),
        "/usr/bin/google-chrome",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
    ]
    for pattern in patterns:
        for hit in sorted(glob.glob(pattern)):
            if os.access(hit, os.X_OK):
                return [hit]
    for name in ("google-chrome", "chromium", "chromium-browser"):
        found = shutil.which(name)
        if found:
            return [found]
    return None


# --------------------------------------------------------------------- WebSocket
class WS:
    """极简 WebSocket 客户端（文本帧，够 CDP 用）。"""

    def __init__(self, url: str, timeout: float = 25.0):
        rest = url[5:]
        hostport, _, path = rest.partition("/")
        host, _, port = hostport.partition(":")
        self.sock = socket.create_connection((host, int(port or 80)), timeout=timeout)
        self.sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(
            f"GET /{path} HTTP/1.1\r\nHost: {hostport}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode()
        )
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("WebSocket 握手失败")
            buf += chunk
        head, _, tail = buf.partition(b"\r\n\r\n")
        if b"101" not in head.split(b"\r\n")[0]:
            raise RuntimeError(f"握手被拒：{head.decode(errors='replace')[:160]}")
        self.buf = tail
        self.next_id = 1

    def _frame(self, payload: bytes) -> None:
        header = bytearray([0x81])
        mask = os.urandom(4)
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < (1 << 16):
            header.append(0x80 | 126)
            header += struct.pack(">H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", length)
        header += mask
        self.sock.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def _exact(self, n: int) -> bytes:
        while len(self.buf) < n:
            chunk = self.sock.recv(1 << 16)
            if not chunk:
                raise RuntimeError("连接被关闭")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self) -> str:
        while True:
            first, second = self._exact(2)
            opcode = first & 0x0F
            length = second & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._exact(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._exact(8))[0]
            payload = self._exact(length) if length else b""
            if opcode == 0x1:
                return payload.decode("utf-8", "replace")
            if opcode == 0x8:
                raise RuntimeError("服务端关闭连接")

    def call(self, method: str, params: dict | None = None, timeout: float = 30.0) -> dict:
        mid = self.next_id
        self.next_id += 1
        self._frame(json.dumps({"id": mid, "method": method, "params": params or {}}).encode())
        deadline = time.time() + timeout
        while time.time() < deadline:
            msg = json.loads(self.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method} 失败：{msg['error']}")
                return msg.get("result", {})
        raise TimeoutError(method)

    def drain(self, seconds: float = 0.5) -> list[dict]:
        events: list[dict] = []
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                events.append(json.loads(self.recv()))
            except (socket.timeout, TimeoutError, RuntimeError):
                break
        return [e for e in events if "method" in e]

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


# --------------------------------------------------------------------- 主流程
STATE_JS = r"""
(function () {
  const outlet = document.getElementById('outlet');
  const txt = (n) => (n ? n.innerText.replace(/\s+/g, ' ').trim() : '');
  return JSON.stringify({
    booted: !!window.__glpmateBooted,
    readyState: document.readyState,
    outletChildren: outlet ? outlet.children.length : -1,
    outletFirstClass: outlet && outlet.firstElementChild ? outlet.firstElementChild.className : '',
    outletText: txt(outlet).slice(0, 1200),
    tabs: document.querySelectorAll('.tab').length,
    cards: document.querySelectorAll('.card').length,
    svgs: document.querySelectorAll('svg').length,
    diag: txt(document.getElementById('diag')).slice(0, 400),
    bodyClass: document.body.className,
    hash: location.hash
  });
})()
"""


def main() -> int:
    url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8848/#/today"
    shot = sys.argv[2] if len(sys.argv) > 2 else ""
    wait = float(sys.argv[3]) if len(sys.argv) > 3 else 4.0
    # 第 4 个参数：加载后要点击的文本（用于打开弹层）
    click_text = sys.argv[4] if len(sys.argv) > 4 else ""

    browser = find_browser()
    if not browser:
        print("找不到 Chrome/Chromium。可设置 GLPMATE_CHROME=/path/to/chrome，"
              "或运行：npx puppeteer browsers install chrome-headless-shell", file=sys.stderr)
        return 2

    port = None
    for candidate in FALLBACK_PORTS:
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", candidate)) != 0:
                port = candidate
                break
    if port is None:
        print("没有空闲的调试端口", file=sys.stderr)
        return 2

    profile = tempfile.mkdtemp(prefix="glpmate-chrome-")
    proc = subprocess.Popen(
        browser + [
            "--headless=new", f"--remote-debugging-port={port}", f"--user-data-dir={profile}",
            "--no-first-run", "--no-default-browser-check", "--disable-gpu",
            "--hide-scrollbars", "--window-size=430,1400", url,
        ],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    console: list[str] = []
    try:
        ws_url = None
        deadline = time.time() + 30
        while time.time() < deadline and ws_url is None:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=2) as resp:
                    for target in json.load(resp):
                        if target.get("type") == "page" and target.get("webSocketDebuggerUrl"):
                            ws_url = target["webSocketDebuggerUrl"]
                            break
            except Exception:  # noqa: BLE001
                pass
            if ws_url is None:
                time.sleep(0.4)
        if ws_url is None:
            print("等不到调试目标", file=sys.stderr)
            return 2

        ws = WS(ws_url)
        ws.call("Runtime.enable")
        ws.call("Log.enable")
        ws.call("Page.enable")
        time.sleep(wait)
        for ev in ws.drain(0.8):
            method, params = ev.get("method"), ev.get("params", {})
            if method == "Runtime.consoleAPICalled":
                args = " ".join(str(a.get("value", a.get("description", a.get("type"))))
                                for a in params.get("args", []))
                console.append(f"[{params.get('type')}] {args}")
            elif method == "Runtime.exceptionThrown":
                d = params.get("exceptionDetails", {})
                console.append(f"[exception] {d.get('text')} "
                               f"{d.get('exception', {}).get('description', '')}".strip())
                if d.get("url"):
                    console.append(f"            at {d['url']}:{d.get('lineNumber')}")
            elif method == "Log.entryAdded":
                e = params.get("entry", {})
                console.append(f"[log:{e.get('level')}] {e.get('text')} {e.get('url', '')}".strip())
        if click_text:
            expr = """(function () {
              const target = %s;
              const nodes = Array.from(document.querySelectorAll('button, .row, .list-row, a, [role=button]'));
              const hit = nodes.find((n) => (n.innerText || '').includes(target));
              if (hit) { hit.click(); return 'clicked'; }
              return 'not-found';
            })()""" % json.dumps(click_text, ensure_ascii=False)
            clicked = ws.call("Runtime.evaluate", {"expression": expr, "returnByValue": True})
            console.append(f"[click] {click_text} -> {clicked['result'].get('value')}")
            time.sleep(1.5)
        state = json.loads(ws.call("Runtime.evaluate", {
            "expression": STATE_JS, "returnByValue": True})["result"]["value"])
        if click_text:
            extra = ws.call("Runtime.evaluate", {
                "expression": "(function(){var s=document.querySelector('.sheet');"
                              "return s? s.innerText.replace(/\\s+/g,' ').slice(0,900):'(没有弹层)';})()",
                "returnByValue": True})
            state["sheetText"] = extra["result"].get("value")
        if shot:
            metrics = ws.call("Page.getLayoutMetrics")
            size = metrics.get("cssContentSize") or metrics.get("contentSize") or {}
            height = int(min(size.get("height", 1400), 8000))
            data = ws.call("Page.captureScreenshot", {
                "format": "png", "captureBeyondViewport": True,
                "clip": {"x": 0, "y": 0, "width": 430, "height": height, "scale": 1},
            })
            Path(shot).write_bytes(base64.b64decode(data["data"]))
        print(json.dumps(state, ensure_ascii=False, indent=2))
        print("\n=== console ===")
        print("\n".join(console) if console else "(无输出)")
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    raise SystemExit(main())
