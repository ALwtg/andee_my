#!/usr/bin/env python3
"""README 封面（1200×630）、GitHub 社交预览（1280×640）与方形组织头像（512×512）。

素材只有一份：`images/brand/andee-head.png`（白底 + 橙粉光晕 + 蓝色头部）。
头部抠图和光晕补底在 `andee_head.py`，和启动图标共用同一套，所以封面上的角色
和桌面图标是**同一张画**，不是"看起来像"。

## 构图

  * 底是纯白（源图四角就是 #FDFDFD），角色连同自带的光晕一起贴上去，
    光晕与白底在边缘处自然融掉 —— 所以不抠光晕，贴整张。
  * 文字深色，左对齐；角色在右。文字右缘到角色**头部**左缘的间距会量出来，
    小于 40px 就告警（标语长了 20px 悄悄压到头上，只有肉眼看得出）。
  * 头像是圆形裁切（GitHub 组织头像就是圆的）：脚本量角色最远点到圆心的
    距离，越界就告警。

文字用 Chrome 渲 SVG（要 PingFang / SF Pro 之类系统字体，PIL 做不好字距）。

用法:
    python3 tools/make_social_preview.py
"""
import argparse
import html
import os
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import andee_head as head  # noqa: E402

REPO = HERE.parent
OUT_DIR = REPO / "images"
CHROME = os.environ.get(
    "CHROME_PATH", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

W, H = 1280, 640
COVER = (1200, 630)
AVATAR = 512

# 角色整图（含光晕）在 1280×640 画布上的落位：整图边长与中心。
ART_SIDE = 760
ART_CX, ART_CY = 952.0, 322.0
# 头部本身的直径（量出来的 693px）按同比例缩放后的值，用于文字间距自检。
HEAD_W_SRC = 693.0

# 文字基线（比原来整体上移 60px，给最下面那条表情行让位）
Y_EYEBROW, Y_TITLE, Y_TAG = 118, 244, 304
Y_RULE, Y_FOOT = 388, 430

# 最下面那条「一张插画 + 一个字」的行：五张横向排开，标签居中在每张下面。
# 宽度锁在左栏里（96 → 700），所以它不会碰到右边那颗头。
# 插画是 App 的手势情绪（WINK / WAVE / PROTECT / REST 那批带道具的），
# 网页渲染器 ball.js 画不出它们，所以这里用的是真图，不是渲出来的。
FLOW_W = 604
FLOW_IMG = 104
FLOW_TOP = 486
FLOW_LABEL_DY = 26
FLOW_FONT = 15
FLOW_DIR = REPO / "images" / "brand" / "illustrations"

INK = "#10172A"
INK_SOFT = "#3B4768"
INK_FAINT = "#6B7794"
ACCENT = "#2F6BEA"

EN = {
    "eyebrow": "ANDROID · AI AGENT",
    "title": "Andee",
    "tagline": ["It becomes the soul", "of the device."],
    "foot": ["Nineteen moods. Its own notebook.", "It reviews itself when nobody watches."],
    "flow": [("wave", "Listen"), ("wink", "Look"), ("think", "Think"),
             ("protect", "Act"), ("rest", "Speak")],
    "font": "'SF Pro Display','Helvetica Neue',Helvetica,Arial,sans-serif",
}
ZH = {
    "eyebrow": "安卓 · AI 智能体",
    "title": "Andee",
    "tagline": ["他会成为这台设备的", "主人。"],
    "foot": ["十九种情绪、一本只有他自己能读的笔记本。", "没人看着的时候，他会自己复盘。"],
    "flow": [("wave", "听"), ("wink", "看"), ("think", "想"),
             ("protect", "做"), ("rest", "说")],
    "font": "'PingFang SC','Hiragino Sans GB','Heiti SC',sans-serif",
}


def art_layer(side: int):
    """整张角色图（带光晕）缩到 side×side。"""
    im = Image.open(head.SRC).convert("RGB")
    return im.resize((side, side), Image.LANCZOS)


def build_svg(copy: dict) -> str:
    f = copy["font"]
    tag = "\n  ".join(
        f'<text x="96" y="{Y_TAG + i * 44}" class="tag" fill="{INK_SOFT}">{html.escape(l)}</text>'
        for i, l in enumerate(copy["tagline"]))
    foot = "\n  ".join(
        f'<text x="96" y="{Y_FOOT + i * 32}" class="foot" fill="{INK_FAINT}">{html.escape(l)}</text>'
        for i, l in enumerate(copy["foot"]))
    cell_w = FLOW_W / len(copy["flow"])
    flow = "\n  ".join(
        f'<text x="{96 + i * cell_w + cell_w / 2:.1f}" y="{FLOW_TOP + FLOW_IMG + FLOW_LABEL_DY}"'
        f' text-anchor="middle" class="flow" fill="{INK_SOFT}">{html.escape(label)}</text>'
        for i, (_, label) in enumerate(copy["flow"]))
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  <defs>
    <style>
      .eyebrow {{ font-family: {f}; font-size: 18px; font-weight: 600; letter-spacing: 5px; }}
      .wordmark {{ font-family: {f}; font-size: 130px; font-weight: 700; letter-spacing: -3px; }}
      .tag {{ font-family: {f}; font-size: 33px; font-weight: 500; letter-spacing: -0.2px; }}
      .foot {{ font-family: {f}; font-size: 22px; font-weight: 400; letter-spacing: 0.1px; }}
      .flow {{ font-family: {f}; font-size: {FLOW_FONT}px; font-weight: 500; letter-spacing: 0.2px; }}
    </style>
  </defs>
  <text x="96" y="{Y_EYEBROW}" class="eyebrow" fill="{ACCENT}">{html.escape(copy["eyebrow"])}</text>
  <text x="92" y="{Y_TITLE}" class="wordmark" fill="{INK}">{html.escape(copy["title"])}</text>
  {tag}
  <rect x="96" y="{Y_RULE}" width="88" height="3" rx="1.5" fill="{ACCENT}"/>
  {foot}
  {flow}
</svg>
'''


def render_text_layer(svg: str, tmp: Path) -> Path:
    page = tmp / "text.html"
    page.write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}'
        'svg{display:block}</style></head><body>' + svg + "</body></html>",
        encoding="utf-8")
    shot = tmp / "text.png"
    if shot.exists():
        shot.unlink()
    subprocess.run(
        [CHROME, "--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
         "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
         "--force-device-scale-factor=1", f"--user-data-dir={tmp}/chrome-profile",
         f"--screenshot={shot}", f"--window-size={W},{H}", f"file://{page}"],
        capture_output=True, text=True, timeout=180, check=False)
    if not shot.exists():
        raise SystemExit("文字层截图失败（Chrome 没起来？）。")
    return shot


def text_right_edge(text_layer: Path) -> int:
    alpha = np.asarray(Image.open(text_layer).convert("RGBA").split()[3])
    cols = np.where((alpha[150:520] > 120).any(axis=0))[0]
    return int(cols.max()) if len(cols) else 0


def _flood(mask, seed):
    """8 连通泛洪，返回该连通块的布尔掩码。"""
    h, w = mask.shape
    seen = np.zeros_like(mask)
    stack = [seed]
    seen[seed] = True
    while stack:
        y, x = stack.pop()
        for dy in (-1, 0, 1):
            yy = y + dy
            if yy < 0 or yy >= h:
                continue
            for dx in (-1, 0, 1):
                xx = x + dx
                if 0 <= xx < w and mask[yy, xx] and not seen[yy, xx]:
                    seen[yy, xx] = True
                    stack.append((yy, xx))
    return seen


def _drop_edge_fragments(im):
    """抹掉贴着画布边缘的连通块，返回 (新图, 抹掉多少像素)。

    这套插画是从一张拼图上切出来的，边上会把**隔壁那只**的一小条一起带进来：
    `protect` 左侧 x=0..30 有 2226px、`rest` 左侧 x=0..41 有 2960px。

    ⚠️ 判据是「**被画布的左边或上边切开**」（`x0 == 0 or y0 == 0`），有两个坑：
    1. **不能按"连不连着球"删** —— `wave` / `wink` 里那几笔橙色动效线本来就脱离主体
       （3979px / 2158px），是画的一部分，按连通删会把它们一起删掉。
    2. **不能写成"贴任意一条边就删"** —— `protect` 的**主体**包围盒是 x=88..512，
       它自己就被画布右边切了；按"贴边"删会把整个主体删掉（第 4 格会变空）。
       真正的规律是：这套图是从拼图上**从左往右**切的，溢出来的邻居只会落在**左边缘**
       （`protect` x=0..30、`rest` x=0..41 都是），所以只认左边和上边。
    """
    a = np.asarray(im).copy()
    mask = a[:, :, 3] > 16
    ys, xs = np.nonzero(mask)
    done = np.zeros_like(mask)
    removed = 0
    for k in range(0, len(ys), 8):       # 抽样做种子就够：连通块比 8px 大得多
        seed = (int(ys[k]), int(xs[k]))
        if done[seed]:
            continue
        comp = _flood(mask, seed)
        done |= comp
        cy, cx = np.nonzero(comp)
        if cx.min() == 0 or cy.min() == 0:
            a[comp, 3] = 0
            removed += int(comp.sum())
    return Image.fromarray(a), removed


def flow_layer(copy: dict):
    """五张插画按同一高度排成一行（源图是 512×512 方图，直接缩，不裁边）。

    刻意**不裁透明边**：这五张是同一套画，球的大小和位置本来就一致，伸出去的是道具
    （挥手、盾牌、手指）和动效线。裁边会把带道具的那几张的包围盒撑宽、按高度归一化之后
    球反而变小 —— 五个排出来一大一小。整张方图一起缩，球的尺寸才是齐的。
    只做一件事：把误切进来的邻居碎片抹掉（见 [_drop_edge_fragments]）。
    """
    cell_w = FLOW_W / len(copy["flow"])
    for i, (key, _) in enumerate(copy["flow"]):
        im, _ = _drop_edge_fragments(Image.open(FLOW_DIR / f"{key}.webp").convert("RGBA"))
        im = im.resize((FLOW_IMG, FLOW_IMG), Image.LANCZOS)
        yield im, (round(96 + i * cell_w + (cell_w - FLOW_IMG) / 2), FLOW_TOP)


def compose(copy, name, tmp, size=None):
    base = Image.new("RGB", (W, H), (253, 253, 253)).convert("RGBA")
    art = art_layer(ART_SIDE).convert("RGBA")
    left, top = int(ART_CX - ART_SIDE / 2), int(ART_CY - ART_SIDE / 2)
    base.alpha_composite(art, (left, top)) if left >= 0 and top >= 0 else base.paste(art, (left, top))
    for im, xy in flow_layer(copy):
        base.alpha_composite(im, xy)
    text_layer = render_text_layer(build_svg(copy), tmp)
    base.alpha_composite(Image.open(text_layer).convert("RGBA"))
    final = base.convert("RGB")

    s = ART_SIDE / 1254.0
    head_left = left + 277 * s
    over = text_right_edge(text_layer)
    gap = head_left - over
    print(f"  文字右缘 {over} / 头部左缘 {head_left:.0f} / 间距 {gap:.0f}px"
          + ("   ⚠ 太挤" if gap < 40 else ""))

    if size and size != (W, H):
        l, t = (W - size[0]) // 2, (H - size[1]) // 2
        final = final.crop((l, t, l + size[0], t + size[1]))
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / name
    final.save(out, "PNG", optimize=True)
    print(f"  -> {out.relative_to(REPO)}  {final.size[0]}x{final.size[1]}  {out.stat().st_size / 1024:.0f}KB")


def make_avatar():
    """方形头像：角色整图居中，圆形裁切下角（耳朵）不越界。"""
    rgba, alpha, hard, a = head.character_rgba()
    r, dy = head.circumcircle(alpha)
    # 外接圆（相对源图中心，已含最优下移）占圆形裁切半径的比例。
    ratio = 0.80
    scale = (AVATAR / 2.0) * ratio / r
    side = int(round(1254 * scale))
    art = Image.open(head.SRC).convert("RGB").resize((side, side), Image.LANCZOS)
    base = Image.new("RGB", (AVATAR, AVATAR), (253, 253, 253))
    ox = int(round(AVATAR / 2.0 - 626.5 * scale))
    oy = int(round(AVATAR / 2.0 - 626.5 * scale + dy * scale))
    base.paste(art, (ox, oy))
    far = r * scale
    print(f"  角色最远点 {far:.0f}px / 圆形裁切半径 {AVATAR / 2:.0f}px / 余量 {AVATAR / 2 - far:.0f}px")
    out = OUT_DIR / "avatar-org.png"
    base.save(out, "PNG", optimize=True)
    print(f"  -> {out.relative_to(REPO)}  {AVATAR}x{AVATAR}  {out.stat().st_size / 1024:.0f}KB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tmp", default="/tmp/andee-social-preview")
    args = ap.parse_args()
    tmp = Path(args.tmp)
    tmp.mkdir(parents=True, exist_ok=True)

    print("英文版 1280×640")
    compose(EN, "social-preview.png", tmp)
    print("中文版 1280×640")
    compose(ZH, "social-preview.zh-CN.png", tmp)
    print("README 封面 1200×630（英文）")
    compose(EN, "cover.png", tmp, size=COVER)
    print("README 封面 1200×630（中文）")
    compose(ZH, "cover.zh-CN.png", tmp, size=COVER)
    print(f"方形头像 {AVATAR}×{AVATAR}（GitHub 组织头像）")
    make_avatar()


if __name__ == "__main__":
    main()
