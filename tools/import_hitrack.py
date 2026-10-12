#!/usr/bin/env python3
"""把 HiTrack 运动记录导入数据库（Termux 里用，也可在电脑上用）。

用法：
  python3 tools/import_hitrack.py /sdcard/Download
  python3 tools/import_hitrack.py /sdcard/Download --dry-run
  python3 tools/import_hitrack.py <目录> --db data/glpmate.db

特点：
  - 按 meta.source_key 去重，**反复运行不会产生重复记录**（重要：用户会反复点）
  - --dry-run 只预览不写库
  - 找不到文件时给出可操作的提示，而不是静默成功
"""

from __future__ import annotations

import argparse
import pathlib
import sys

# 用脚本自身位置推导项目根目录，而不是依赖当前工作目录——
# 否则从 ~/glpmate-lite 以外的目录调用会 ModuleNotFoundError。
_ROOT = pathlib.Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from glpmate import hitrack          # noqa: E402
from glpmate.store import Store      # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="导入华为运动健康 HiTrack 文件")
    parser.add_argument("directory", help="HiTrack_* 文件所在目录")
    parser.add_argument("--db", default=None,
                        help="数据库路径（默认 项目内 data/glpmate.db，与脚本位置无关）")
    parser.add_argument("--dry-run", action="store_true", help="只预览，不写库")
    parser.add_argument("--verbose", action="store_true", help="逐个文件打印明细")
    args = parser.parse_args(argv)

    root = pathlib.Path(args.directory)
    if not root.is_dir():
        print(f"❌ 目录不存在：{root}")
        print("   华为的 HiTrack 文件在 /data/data/com.huawei.health/files/，需要 root 复制出来：")
        print('   su -c "cp /data/data/com.huawei.health/files/HiTrack_* /sdcard/Download/"')
        return 2

    files = hitrack.find_hitrack_files(root)
    if not files:
        print(f"⚠️  在 {root} 里没有找到 HiTrack_* 文件。")
        print("   注意：这个文件是在**运动健康 App 里打开某次运动详情**时才生成的。")
        print("   请先在手机上打开一次运动记录，然后再运行本命令。")
        return 1

    db_path = pathlib.Path(args.db) if args.db else _ROOT / "data" / "glpmate.db"
    store = None if args.dry_run else Store(db_path)
    total_written = total_skipped = total_suspect = 0

    for path in files:
        try:
            workout = hitrack.parse_file(path)
        except (OSError, ValueError) as exc:
            print(f"  ⚠️  {path.name} 解析失败：{exc}")
            continue
        records = hitrack.to_records(workout, source=path.name)
        if not records:
            if args.verbose:
                print(f"  –  {path.name}：没有可导入的内容")
            continue

        stats = workout.hr_stats()
        suspect = bool((records[0].get("meta") or {}).get("suspect"))
        flag = " ⚠️ 数据存疑" if suspect else ""
        line = (f"  {path.name[:34]:34s} 心率 {stats['count']:>4d} 点 "
                f"{stats['min']:.0f}–{stats['max']:.0f} bpm 均值 {stats['avg']}{flag}")

        written = skipped = 0
        for rec in records:
            key = (rec.get("meta") or {}).get("source_key")
            if store is not None and key and store.has_source_key(key):
                skipped += 1
                continue
            if store is not None:
                store.add_record(rec, source="hitrack")
            written += 1
        total_written += written
        total_skipped += skipped
        total_suspect += int(suspect)
        if args.verbose or written or suspect:
            print(line + (f"  → 写入 {written}，跳过重复 {skipped}" if not args.dry_run else ""))

    verb = "将写入" if args.dry_run else "已写入"
    print()
    print(f"{verb} {total_written} 条记录，跳过重复 {total_skipped} 条"
          + (f"，其中 {total_suspect} 个文件数据存疑" if total_suspect else ""))
    if args.dry_run:
        print("（--dry-run 预览模式，没有改动数据库）")
    else:
        print("⚠️  HiTrack 只含手动记录的运动，不含 24 小时连续心率与血氧。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
