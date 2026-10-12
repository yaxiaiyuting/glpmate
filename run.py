"""启动入口：python3 run.py

常用参数：
  --host 0.0.0.0      让手机在同一局域网内访问
  --port 8848
  --db data/xxx.db    指定数据库
  --token 口令        开启访问口令（暴露到局域网时建议开）
  --seed              首次运行时写入一份示例数据，方便先看界面
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from glpmate import __version__, config as cfg          # noqa: E402
from glpmate.seed import seed_demo                      # noqa: E402
from glpmate.server import local_ip, make_server        # noqa: E402
from glpmate.store import Store                         # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=f"{cfg.APP_NAME} {__version__}（{cfg.LICENSE}）")
    parser.add_argument("--host", default=cfg.DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=cfg.DEFAULT_PORT)
    parser.add_argument("--db", default=str(cfg.DEFAULT_DB),
                        help=f"数据库路径（默认 {cfg.DEFAULT_DB}）")
    parser.add_argument("--static", default=None,
                        help="前端静态文件目录（默认项目内 static/，可用 GLPMATE_STATIC 覆盖）")
    parser.add_argument("--token", default=None, help="访问口令；留空则不校验")
    parser.add_argument("--seed", action="store_true", help="写入示例数据")
    parser.add_argument("--import-hitrack", metavar="DIR",
                        help="导入华为运动健康的 HiTrack 运动记录（明文文件，目录里找 HiTrack_*）")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.WARNING if args.quiet else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    store = Store(args.db)
    if args.import_hitrack:
        from glpmate import hitrack
        files = hitrack.find_hitrack_files(args.import_hitrack)
        total = 0
        for path in files:
            try:
                records = hitrack.to_records(hitrack.parse_file(path), source=path.name)
            except (OSError, ValueError) as exc:
                print(f"  跳过 {path.name}：{exc}")
                continue
            for rec in records:
                store.add_record(rec, source="hitrack")
            total += len(records)
            print(f"  {path.name} → {len(records)} 条")
        print(f"HiTrack 导入完成：{len(files)} 个文件，写入 {total} 条记录")
        print("  注意：HiTrack 只含手动记录的运动，不含 24 小时连续心率与血氧。")
    if args.seed:
        created = seed_demo(store)
        print(f"示例数据：写入 {created} 条记录")

    static_dir = Path(args.static) if args.static else cfg.STATIC_DIR
    if not (static_dir / "index.html").is_file():
        print(f"⚠️  在 {static_dir} 里没找到 index.html，界面会显示占位页。")
    httpd = make_server(args.host, args.port, store=store, token=args.token,
                        static_dir=static_dir)
    host, port = httpd.server_address[0], httpd.server_address[1]
    shown = "127.0.0.1" if host in ("0.0.0.0", "::") else host
    print()
    print(f"  {cfg.APP_NAME} {__version__}   许可证 {cfg.LICENSE}")
    print(f"  数据库：{Path(args.db).resolve()}")
    print(f"  本机访问：http://{shown}:{port}/")
    if host in ("0.0.0.0", "::"):
        print(f"  手机访问：http://{local_ip()}:{port}/   （同一 Wi-Fi；可加到主屏幕当 App 用）")
        if not args.token:
            print("  提示：已监听所有网卡但没有设口令，同网段的人都能读写你的记录。"
                  "建议加 --token 你的口令")
    print(f"  接口自检：http://{shown}:{port}/api/health")
    print("  Ctrl+C 停止\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
