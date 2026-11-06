# -*- coding: utf-8 -*-
"""生成电视端兼容的 PNG 图标和 banner（替换 vector drawable）"""
from PIL import Image, ImageDraw, ImageFont
import os

RES = r"C:/Users/Administrator/WorkBuddy/电视锁屏/app/src/main/res"
ASSETS = r"C:/Users/Administrator/WorkBuddy/电视锁屏/app/src/main/assets"

# 深蓝主题色
BG_DARK = (13, 27, 42)      # #0D1B2A
BG_ICON = (27, 38, 59)      # #1B263B
WHITE   = (224, 225, 221)   # #E0E1DD
GREY    = (119, 141, 169)   # #778DA9
ACCENT  = (83, 133, 181)    # #5385B5

FONT_PATH = r"C:/Windows/Fonts/msyh.ttc"  # 微软雅黑

def get_font(size):
    try:
        return ImageFont.truetype(FONT_PATH, size)
    except Exception:
        return ImageFont.load_default()

def draw_lock(d, cx, cy, w, color=WHITE, bg=BG_ICON):
    """在 (cx,cy) 居中画一个锁，锁宽 w"""
    body_w = int(w * 0.6)
    body_h = int(w * 0.5)
    body_x = cx - body_w // 2
    body_y = cy - body_h // 4 + int(w*0.05)
    # 锁环（上半圆，空心）
    shackle_r = int(body_w * 0.32)
    shackle_cx = cx
    shackle_cy = body_y
    arc_box = [shackle_cx - shackle_r, shackle_cy - shackle_r - int(w*0.12),
               shackle_cx + shackle_r, shackle_cy + shackle_r - int(w*0.12)]
    d.arc(arc_box, start=180, end=360, fill=color, width=max(2, int(w*0.06)))
    # 锁体（实心方块）
    d.rounded_rectangle([body_x, body_y, body_x + body_w, body_y + body_h],
                        radius=max(2, int(w*0.08)), fill=color)
    # 锁孔（背景色小圆）
    key_r = max(1, int(w * 0.06))
    d.ellipse([cx - key_r, body_y + int(body_h*0.3),
               cx + key_r, body_y + int(body_h*0.3) + key_r*2], fill=bg)

def make_icon(size, path, lock_scale=1.0, square_bg=False):
    img = Image.new("RGBA", (size, size), BG_ICON + (255,))
    d = ImageDraw.Draw(img)
    # 背景圆角（maskable 图标用纯方形背景，避免系统裁切后出现透明角）
    if square_bg:
        d.rectangle([0, 0, size, size], fill=BG_ICON)
    else:
        d.rounded_rectangle([0, 0, size, size], radius=max(2, size//6), fill=BG_ICON)
    # 锁（maskable 需缩小到安全区内，避免被系统圆形裁切切掉）
    draw_lock(d, size//2, size//2, int(size * lock_scale))
    img.save(path)
    print(f"  icon {size}x{size} -> {path}")

def make_banner(path):
    W, H = 320, 180
    img = Image.new("RGBA", (W, H), BG_DARK + (255,))
    d = ImageDraw.Draw(img)
    # 左侧锁图标区
    lock_cx, lock_cy = 70, H // 2
    draw_lock(d, lock_cx, lock_cy, 90, color=WHITE, bg=BG_DARK)
    # 右侧文字
    x0 = 140
    # 标题 "儿童锁屏"
    f1 = get_font(40)
    d.text((x0, 45), "儿童锁屏", font=f1, fill=WHITE)
    # 副标题
    f2 = get_font(20)
    d.text((x0, 95), "Kids TV Lock", font=f2, fill=GREY)
    d.text((x0, 122), "开机自启 · 定时锁屏 · 认字解锁", font=get_font(14), fill=GREY)
    img.save(path)
    print(f"  banner {W}x{H} -> {path}")

# 1. 各密度 icon PNG
icon_sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for d, s in icon_sizes.items():
    p = os.path.join(RES, d)
    os.makedirs(p, exist_ok=True)
    make_icon(s, os.path.join(p, "ic_launcher.png"))
    # 也生成 ic_launcher_round（部分桌面用）
    make_icon(s, os.path.join(p, "ic_launcher_round.png"))

# 2. banner PNG（电视端 Leanback 用，放 xhdpi）
bdir = os.path.join(RES, "drawable-xhdpi")
os.makedirs(bdir, exist_ok=True)
make_banner(os.path.join(bdir, "tv_banner.png"))

# 3. 手机端 PWA 图标（放到桌面时用，assets 里由 HTTP 服务直接吐出）
os.makedirs(ASSETS, exist_ok=True)
make_icon(192, os.path.join(ASSETS, "icon-192.png"))
make_icon(512, os.path.join(ASSETS, "icon-512.png"))
# Android 自适应/maskable 图标：内容缩进到安全区，纯方形底
make_icon(512, os.path.join(ASSETS, "icon-maskable-512.png"), lock_scale=0.62, square_bg=True)
# iOS 添加到主屏幕（apple-touch-icon 不支持透明，会自动加圆角，用不透明方形底）
make_icon(180, os.path.join(ASSETS, "apple-touch-icon.png"), square_bg=True)
make_icon(64, os.path.join(ASSETS, "favicon.png"))

print("\n✅ 所有 PNG 生成完毕（电视图标 + 手机 PWA 图标）")
