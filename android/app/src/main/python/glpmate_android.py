"""Android 内嵌启动入口（Chaquopy 调用）。

APK 里没有"当前工作目录"这个概念，也不该往 python 目录写数据，所以这个模块
只做一件事：把路径全参数化，然后在一个后台线程里跑 HTTP 服务。

关键点：
  - 服务只绑定 127.0.0.1，数据不出设备；
  - 数据库落在 filesDir/data（可写、且不会被重新解压覆盖）；
  - 前端静态文件来自解压出来的 assets；
  - 这里**不调用** sys.exit / signal，Android 线程里那些会直接把进程干掉。
"""

from __future__ import annotations

import os
import sys
import threading
import time

_server = None
_thread = None
_last_error = ""


def _prepare_paths(code_dir: str) -> None:
    """把后端代码目录加进 sys.path（glpmate 包就在里面）。"""
    if code_dir and code_dir not in sys.path:
        sys.path.insert(0, code_dir)


def start_server(static_dir: str, code_dir: str, data_dir: str, port: int = 8848,
                 quiet: bool = True) -> str:
    """启动服务并立刻返回（不阻塞调用线程）。

    返回一段中文状态说明，供 Java 侧记日志。
    """
    global _server, _thread, _last_error

    if _server is not None:
        return f"服务已在运行：http://127.0.0.1:{port}/"

    _prepare_paths(code_dir)
    # 必须在 import glpmate 之前设好环境变量：config.py 在导入时就会读它们
    os.environ["GLPMATE_STATIC"] = static_dir
    os.environ["GLPMATE_DATA"] = data_dir

    try:
        import logging
        logging.basicConfig(level=logging.WARNING if quiet else logging.INFO,
                            format="%(levelname)s %(name)s: %(message)s")

        from glpmate.server import make_server
        from glpmate.store import Store

        db_path = os.path.join(data_dir, "glpmate.db")
        store = Store(db_path)
        # 端口固定 8848：绑 127.0.0.1，外部网络访问不到
        _server = make_server("127.0.0.1", port, store=store,
                              static_dir=static_dir, port_retries=1)

        def serve() -> None:
            global _last_error
            try:
                _server.serve_forever(poll_interval=0.5)
            except Exception as exc:                      # noqa: BLE001
                _last_error = f"{type(exc).__name__}: {exc}"
                print(f"[glpmate] 服务线程退出：{_last_error}", flush=True)

        _thread = threading.Thread(target=serve, name="glpmate-http", daemon=True)
        _thread.start()

        # 等到真的能连上再返回，Java 侧的健康检查才有意义
        deadline = time.time() + 20
        while time.time() < deadline:
            if _health_ok(port):
                return (f"已启动：http://127.0.0.1:{port}/  "
                        f"数据目录 {data_dir}  数据库 {db_path}")
            if _last_error:
                return f"启动失败：{_last_error}"
            time.sleep(0.2)
        return "服务线程已启动，但 20 秒内没通过健康检查"
    except Exception as exc:                              # noqa: BLE001
        _last_error = f"{type(exc).__name__}: {exc}"
        import traceback
        traceback.print_exc()
        return f"启动异常：{_last_error}"


def _health_ok(port: int) -> bool:
    import urllib.request
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/health", timeout=2) as resp:
            return resp.status == 200
    except Exception:                                     # noqa: BLE001
        return False


def stop_server() -> str:
    """停止服务（给"退出应用"用；一般不需要调）。"""
    global _server
    if _server is None:
        return "没有在运行的服务"
    try:
        _server.shutdown()
        _server.server_close()
        _server = None
        return "已停止"
    except Exception as exc:                              # noqa: BLE001
        return f"停止失败：{exc}"


def server_status() -> str:
    return _last_error or ("running" if _server is not None else "stopped")
