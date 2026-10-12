"""默认配置与常量。

所有可调项都以 settings 表里的键值对为准，这里只放"没有配过时用什么"。
用户可以在界面上改，也可以直接改库；环境变量优先级最高，方便容器部署。
"""

from __future__ import annotations

import os
from pathlib import Path

from . import drugs

APP_NAME = "GLP Mate Lite"
LICENSE = "GPL-3.0-or-later"

# 项目根目录（glpmate/config.py -> 上一级）
ROOT = Path(__file__).resolve().parent.parent

# 静态文件目录：默认为项目内 static/。嵌进 Android APK（Chaquopy）时，
# 前端资源在 assets 解压目录，由 GLPMATE_STATIC 指向。
STATIC_DIR = Path(os.environ.get("GLPMATE_STATIC") or (ROOT / "static"))

# 数据目录：默认项目内 data/；APK 里指向应用私有可写目录（GLPMATE_DATA）。
# 注意不能放在解压出来的 assets 里——那里会被覆盖，数据会丢。
_data_env = os.environ.get("GLPMATE_DATA")
DATA_DIR = Path(_data_env) if _data_env else (ROOT / "data")
DEFAULT_DB = DATA_DIR / "glpmate.db"

# 服务默认监听地址。默认只绑本机；要手机访问就 GLPMATE_HOST=0.0.0.0
DEFAULT_HOST = os.environ.get("GLPMATE_HOST", "127.0.0.1")
DEFAULT_PORT = int(os.environ.get("GLPMATE_PORT", "8848"))

# ---------------------------------------------------------------- 模型默认值
# 任何 OpenAI 兼容端点都能用。DeepSeek 官方地址见 README。
DEFAULT_AI_BASE_URL = "https://api.deepseek.com"
# deepseek-chat / deepseek-reasoner 已于 2026-07-24 下线，当前在售的是这两个。
# 老名字是否仍可用不确定（我这边只有无效 key，探测不出模型是否存在），
# 所以设置页给了下拉选项，模型名随时可改。
DEFAULT_AI_MODEL = "deepseek-flash"
MODEL_PRESETS = [
    {"value": "deepseek-flash", "label": "deepseek-flash（V4.1 Flash，支持读图片）"},
    {"value": "deepseek-v4-pro", "label": "deepseek-v4-pro（V4 Pro，不支持图片）"},
    {"value": "deepseek-chat", "label": "deepseek-chat（旧名，可能已下线）"},
    {"value": "deepseek-reasoner", "label": "deepseek-reasoner（旧名，可能已下线）"},
]

# 视觉模型默认跟随文本模型：flash 本身就能读图，不必单独配。
# 只有当文本模型不支持图片时，才需要在这里单独指定一个视觉端点。
DEFAULT_VISION_BASE_URL = ""
DEFAULT_VISION_MODEL = ""

# 教练每日提问额度（自托管就是自己给自己限速，防止手滑烧 token）
DEFAULT_DAILY_QUOTA = 20
DEFAULT_TEMPERATURE = 0.6
DEFAULT_TIMEOUT_SECONDS = 120

# ---------------------------------------------------------------- 治疗默认值
# 药代参数不写死在这里：它们来自 glpmate/drugs.py，每一项都带说明书出处。
_DEFAULT_PK = drugs.pk_profile(drugs.DEFAULT_DRUG)

DEFAULT_PROFILE = {
    "display_name": "朋友",
    "email": "",
    "drug_id": _DEFAULT_PK["drug_id"],
    "med_name": _DEFAULT_PK["med_name"],          # 替尔泊肽 / 穆峰达
    "dose_mg": _DEFAULT_PK["dose_mg"],            # 起始 2.5 mg
    "dose_interval_days": _DEFAULT_PK["dose_interval_days"],
    "dose_weekday": 0,                            # 0=周一
    "dose_time": "12:00",
    "half_life_hours": _DEFAULT_PK["half_life_hours"],   # 约 5 天（FDA 说明书）
    "tmax_hours": _DEFAULT_PK["tmax_hours"],             # 中位 24 小时（FDA 说明书）
    "treatment_start": None,    # None = 用第一条剂量记录的日期
    "target_weight_kg": 80.0,
    "start_weight_kg": None,
    "height_cm": None,
    "water_target_l": 2.0,
    "daily_quota": DEFAULT_DAILY_QUOTA,
}

# 每日营养目标（可在界面上改）
DEFAULT_TARGETS = {
    "calories": 1600.0,
    "protein_g": 90.0,
    "carbs_g": 180.0,
    "fat_g": 55.0,
    "fiber_g": 25.0,
    "water_ml": 2000.0,
}

# 每日固定日程（今天页的"窗口开启"提示用）
DEFAULT_SCHEDULE = [
    {"key": "water", "label": "饮水", "start": "08:00", "end": "22:00", "enabled": True},
    {"key": "breakfast", "label": "早餐", "start": "06:00", "end": "10:00", "enabled": True},
    {"key": "lunch", "label": "午餐", "start": "11:00", "end": "14:30", "enabled": True},
    {"key": "dinner", "label": "晚餐", "start": "17:00", "end": "21:00", "enabled": True},
    {"key": "weigh_in", "label": "称重", "start": "06:30", "end": "09:30", "enabled": True},
]

# 快速记录的类型（截图里那一排圆形按钮）
QUICK_TYPES = [
    {"key": "weight", "label": "体重", "unit": "kg"},
    {"key": "body_fat", "label": "体脂", "unit": "%"},
    {"key": "waist", "label": "腰围", "unit": "cm"},
    {"key": "bp", "label": "血压", "unit": "mmHg"},
    {"key": "glucose", "label": "血糖", "unit": "mmol/L"},
    {"key": "hba1c", "label": "糖化", "unit": "%"},
    {"key": "lipid", "label": "血脂", "unit": "mmol/L"},
    {"key": "liver", "label": "肝功能", "unit": "U/L"},
    {"key": "sleep", "label": "睡眠", "unit": "h"},
    {"key": "training", "label": "训练", "unit": "min"},
    {"key": "side_effect", "label": "副作用", "unit": "级"},
    {"key": "symptom", "label": "症状", "unit": ""},
    {"key": "ketone", "label": "酮体", "unit": "mmol/L"},
    {"key": "muscle", "label": "肌肉量", "unit": "kg"},
    {"key": "note", "label": "备注", "unit": ""},
]

# 常见副作用（今天页的 chips）
SIDE_EFFECT_CHIPS = ["我很好", "恶心", "便秘", "疲劳", "腹泻", "腹胀", "头痛", "食欲不振"]

# 日历上每种记录对应的点颜色（与前端配色一致）
DOT_COLORS = {
    "dose": "#E8A33D",
    "weight": "#3DBFA0",
    "food": "#E8607A",
    "water": "#4A9BE8",
    "training": "#3DBFA0",
    "side_effect": "#E8607A",
    "sleep": "#8B7BD8",
}

WEEKDAY_CN = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
WEEKDAY_SHORT_CN = ["一", "二", "三", "四", "五", "六", "日"]
MONTH_CN = ["一月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "十一月", "十二月"]
