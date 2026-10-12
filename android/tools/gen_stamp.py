#!/usr/bin/env python3
"""把 assets 里 Python 代码的内容哈希写进 PythonBackend.kt。

为什么需要它：解压戳记必须随代码变化，否则设备上会一直跑旧代码。
本项目踩过两次这个坑——改了 store.py、APK 也重装了，但解压目录里的文件
没更新，界面表现和改动前完全一样，而且**没有任何报错**。

手动更新哈希不可靠（人会忘，我自己就忘了）。所以由构建流程自动跑：

    python3 tools/gen_stamp.py && gradle assembleDebug

不跑也能编译（Kotlin 里那份只是默认值），但戳记可能过期。
用 --check 可以在 CI 里校验它是否最新。
"""
import hashlib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CODE = ROOT / "app/src/main/assets/pyapp/code"
KT = ROOT / "app/src/main/java/com/glpmate/native_app/data/PythonBackend.kt"


def digest() -> str:
    h = hashlib.sha256()
    files = sorted(CODE.rglob("*.py"))
    if not files:
        sys.exit(f"没有找到 Python 源码：{CODE}")
    for f in files:
        h.update(f.relative_to(CODE).as_posix().encode())
        h.update(f.read_bytes())
    return h.hexdigest()[:12]


def main() -> int:
    want = f"code-{digest()}"
    text = KT.read_text()
    m = re.search(r'private const val STAMP_VERSION = "([^"]+)"', text)
    if not m:
        sys.exit(f"没在 {KT.name} 里找到 STAMP_VERSION")
    if m.group(1) == want:
        print(f"戳记已是最新：{want}")
        return 0
    if "--check" in sys.argv:
        print(f"戳记过期：源码里是 {m.group(1)}，应为 {want}")
        return 1
    KT.write_text(text.replace(m.group(0), f'private const val STAMP_VERSION = "{want}"'))
    print(f"戳记已更新：{m.group(1)} → {want}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
