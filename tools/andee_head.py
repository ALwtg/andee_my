#!/usr/bin/env python3
"""从 images/brand/andee-head.png 取出角色：头部抠图、五官遮罩、光晕底片。

源图是一张**不透明**的方图：白底，头部外面一圈橙→粉的光晕，头部本身是蓝色。
没有 alpha，所以这里要自己抠 —— 抠法是量出来的，不是调出来的：

  * 光晕和头部在**色相**上是分开的：光晕的 R 通道约 253，头部的 R 在 27–110。
    光晕环 = `R-G > 14 且 R-B > -5`，它把头部整圈包住，所以"光晕环内侧、与头部
    中心连通的那一块"就是头部。这比任何亮度阈值都稳 —— 头部边缘有一圈偏白的
    亮边（rim light），亮度阈值会把它当背景吃掉。
  * 边缘 alpha 用到光晕环的距离做软过渡，宽 2px，再对**颜色**做去色边：
    边缘像素混了光晕的橙，直接缩小会在头部外沿留一圈橙边（48px 图标上就是脏边）。

这个模块只负责"素材"，不写任何文件；`make_launcher_icon.py` 和
`make_social_preview.py` 各自 import 它。
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "images" / "brand" / "andee-head.png"

# 光晕环的判据。数是从源图上量的：头部蓝 (27,114,240)、橙晕 (253,195,180)、
# 粉晕 (253,172,217)，边缘亮边 (254,254,253)。亮边的 R-G 只有 ~1，不会被当成环。
RING_RG = 14
RING_RB = -5

# 五官亮度阈值（Rec.709 亮度，头部内）。瞳孔 0–33，嘴 0–33，头部蓝身 70–140，
# 高光点 > 200。落在 33 和 70 之间的空隙里取 50。
DARK_LUM = 50.0


def load_src() -> np.ndarray:
    return np.asarray(Image.open(SRC).convert("RGB")).astype(np.float64)


def _flood(free: np.ndarray, seed):
    """4 邻域泛洪。不引 scipy —— 这个仓库的图片工具链只依赖 numpy + PIL。"""
    H, W = free.shape
    seen = np.zeros((H, W), bool)
    stack = [seed]
    seen[seed] = True
    while stack:
        y, x = stack.pop()
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if 0 <= ny < H and 0 <= nx < W and free[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True
                stack.append((ny, nx))
    return seen


def head_matte(a: np.ndarray):
    """返回 (alpha[0..1], 头部硬遮罩)。"""
    R, G, B = a[..., 0], a[..., 1], a[..., 2]
    ring = ((R - G) > RING_RG) & ((R - B) > RING_RB)
    H, W = R.shape
    hard = _flood(~ring, (H // 2 + 20, W // 2))
    # 软边：硬遮罩模糊后取 [0.25,0.75] 重映射，得到约 2px 的过渡。
    soft = Image.fromarray((hard * 255).astype(np.uint8), "L").filter(
        ImageFilter.GaussianBlur(1.1))
    s = np.asarray(soft).astype(np.float64) / 255.0
    alpha = np.clip((s - 0.25) / 0.5, 0.0, 1.0)
    return alpha, hard


def decontaminate(a: np.ndarray, hard: np.ndarray) -> np.ndarray:
    """边缘像素的颜色换成"往头部里面 3px"的颜色，去掉光晕渗进来的橙边。"""
    m = hard.copy()
    for _ in range(3):
        m = m & np.roll(m, 1, 0) & np.roll(m, -1, 0) & np.roll(m, 1, 1) & np.roll(m, -1, 1)
    inner = a.copy()
    inner[~m] = np.nan
    # 向外最多 4 轮，每轮用 4 邻域里已知像素的均值填边缘外一圈。
    known = m.copy()
    out = a.copy()
    filled = np.where(m[..., None], a, 0.0)
    for _ in range(5):
        acc = np.zeros_like(a)
        cnt = np.zeros(a.shape[:2])
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            k = np.roll(known, (dy, dx), (0, 1))
            acc += np.where(k[..., None], np.roll(filled, (dy, dx), (0, 1)), 0.0)
            cnt += k
        new = (~known) & (cnt > 0)
        filled[new] = acc[new] / cnt[new][:, None]
        known = known | new
    edge = known & ~m
    out[edge] = filled[edge]
    return out


def character_rgba():
    """透明底的角色 RGBA（uint8），和它的 alpha / 硬遮罩 / 原图。"""
    a = load_src()
    alpha, hard = head_matte(a)
    rgb = decontaminate(a, hard)
    rgba = np.dstack([np.clip(rgb, 0, 255), alpha * 255.0]).astype(np.uint8)
    return rgba, alpha, hard, a


def luminance(a: np.ndarray) -> np.ndarray:
    return 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]


def face_holes(a: np.ndarray, hard: np.ndarray):
    """眼睛（两颗）与嘴的镂空遮罩，给单色（主题）图标用。

    眼睛里有一颗高光点（亮度 254），阈值 mask 会在洞里留一个疙瘩，所以眼睛用
    "暗像素的 bbox 拟合椭圆"，嘴用阈值 mask 做闭运算 —— 嘴是弧，细，椭圆会把
    背景一起拖进来。耳朵（头顶的角）不会命中：亮度 > 100。
    """
    lum = luminance(a)
    H, W = lum.shape
    yy, xx = np.mgrid[0:H, 0:W]
    dark = hard & (lum < DARK_LUM)
    cx = W / 2.0
    holes = np.zeros((H, W), bool)

    def ellipse(box, grow):
        x0, y0, x1, y1 = box
        ex, ey = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        ax, ay = (x1 - x0 + 1) / 2.0 * grow, (y1 - y0 + 1) / 2.0 * grow
        return ((xx - ex) / ax) ** 2 + ((yy - ey) / ay) ** 2 <= 1.0

    boxes = []
    # 眼睛在 y<760，嘴在 y>760；按 x 分左右。
    for lo, hi in ((0, cx), (cx, W)):
        sel = dark & (yy < 760) & (xx >= lo) & (xx < hi)
        if sel.sum() < 2000:
            raise SystemExit("找不到眼睛：阈值或源图变了，先看 head_matte 的结果。")
        ys, xs = np.where(sel)
        # 眼窝外的细暗线（头部亮边的内沿）会把 bbox 撑大 —— 取 1%–99% 分位。
        x0, x1 = np.percentile(xs, [0.5, 99.5])
        y0, y1 = np.percentile(ys, [0.5, 99.5])
        boxes.append((x0, y0, x1, y1))
        holes |= ellipse((x0, y0, x1, y1), 1.04)

    mouth = dark & (yy >= 760) & (yy < 840)
    for _ in range(2):
        mouth = mouth | np.roll(mouth, 1, 0) | np.roll(mouth, -1, 0) | np.roll(mouth, 1, 1) | np.roll(mouth, -1, 1)
    for _ in range(2):
        mouth = mouth & np.roll(mouth, 1, 0) & np.roll(mouth, -1, 0) & np.roll(mouth, 1, 1) & np.roll(mouth, -1, 1)
    holes |= mouth
    return holes, boxes, mouth


def circumcircle(alpha: np.ndarray, thr: float = 0.03):
    """让"到画布中心最大距离"最小的下移量 —— 同 make_launcher_icon 的 best_shift。

    返回 (半径, 需要下移 dy)。角在头顶两侧，外接圆比头本身大，下移后更贴。
    """
    ys, xs = np.where(alpha > thr)
    H, W = alpha.shape
    ccx, ccy = (W - 1) / 2.0, (H - 1) / 2.0
    best = (float(np.hypot(xs - ccx, ys - ccy).max()), 0)
    for dy in range(-160, 161):
        d = float(np.hypot(xs - ccx, (ys + dy) - ccy).max())
        if d < best[0]:
            best = (d, dy)
    return best


if __name__ == "__main__":
    rgba, alpha, hard, a = character_rgba()
    ys, xs = np.where(alpha > 0.03)
    print(f"头部 bbox x[{xs.min()},{xs.max()}] y[{ys.min()},{ys.max()}]  "
          f"{xs.max() - xs.min() + 1}×{ys.max() - ys.min() + 1}")
    holes, boxes, mouth = face_holes(a, hard)
    for i, b in enumerate(boxes):
        print(f"眼{i + 1} bbox x[{b[0]:.0f},{b[2]:.0f}] y[{b[1]:.0f},{b[3]:.0f}]")
    print(f"嘴 {int(mouth.sum())}px")
    r, dy = circumcircle(alpha)
    print(f"外接圆半径 {r:.1f}，最优下移 {dy}px")
    out = Path("/tmp/andee-brand")
    out.mkdir(exist_ok=True)
    Image.fromarray(rgba, "RGBA").save(out / "character.png")
    chk = Image.new("RGBA", rgba.shape[1::-1], (40, 40, 48, 255))
    chk.alpha_composite(Image.fromarray(rgba, "RGBA"))
    chk.convert("RGB").resize((627, 627), Image.LANCZOS).save(out / "character_on_dark.png")
    ov = a.copy()
    ov[holes] = (255, 0, 80)
    Image.fromarray(ov.astype(np.uint8)).crop((380, 570, 900, 830)).save(out / "holes.png")
