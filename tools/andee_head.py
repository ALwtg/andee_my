#!/usr/bin/env python3
"""从品牌美术稿里取出角色：头部抠图、五官遮罩、光晕底片。

两种源图，两条路：

  * `images/brand/andee-head.png`（**不透明**）—— 白底 + 橙粉光晕 + 蓝色头部，
    走 `character_rgba()`：自己抠。
  * `images/brand/illustrations/*.webp`（**自带 alpha**）—— 已经抠好的插画，
    走 `alpha_source()`：直接用它的 alpha，但必须先清掉 lossy WebP 的抖动噪声。

第一条路（不透明源）的抠法是量出来的，不是调出来的：

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


# ================================================================ 自带 alpha 的插画
#
# think.webp / wink.webp 这一类是**已经抠好**的插画（直通 alpha，实测：把半透明的
# 边缘像素按直通和按预乘各解释一遍，直通与内侧实心邻居的色差 45.8，预乘 112.9，
# 分辨力 2.46×）。所以不再需要 head_matte。
#
# 但它是 **lossy WebP**：全画布 9.6% 的像素带着 alpha 1–7 的抖动噪声散在四角。
# 不清掉的话，底片上会浮一层几乎看不见但洗不掉的灰纱，而且"主体外接圆"会被这些
# 角落噪点撑到整张画布。噪声上限与真实边缘之间是空的（alpha=1 占 21919px，
# alpha=2 起断崖），所以砍在 8。
ALPHA_FLOOR = 8


def alpha_source(path) -> tuple:
    """自带 alpha 的插画 -> (rgb, alpha[0..1])。alpha 走直通，RGB 原样。"""
    a = np.asarray(Image.open(path).convert("RGBA")).astype(np.float64)
    al = a[..., 3]
    return a[..., :3].copy(), np.where(al < ALPHA_FLOOR, 0.0, al / 255.0)


def ball_fit(hard: np.ndarray) -> tuple:
    """主体里那个"球"的圆心与半径 —— 光晕是以球为光源向外发的。

    取**最宽的一行**当赤道：角长在球顶、手压在球底，都比球窄，所以最宽行一定是
    球本身。拿它当光源中心，光晕才是绕着球的一圈，而不是绕着"外接圆"的一圈
    （外接圆被头顶的角撑大、被手往下拽，用它做极坐标会让光晕歪掉）。
    """
    ys = np.where(hard.any(axis=1))[0]
    best = (-1, 0, 0, 0)
    for y in ys:
        xs = np.where(hard[y])[0]
        w = xs.max() - xs.min() + 1
        if w > best[0]:
            best = (w, y, xs.min(), xs.max())
    w, cy, x0, x1 = best
    return (x0 + x1) / 2.0, float(cy), w / 2.0


def _circ_smooth(tab: np.ndarray, sigma_deg: float) -> np.ndarray:
    """把一张 360×3 的方位表按角度做环形高斯平滑。"""
    sd = max(sigma_deg, 1e-3)
    half = int(3.0 * sd)
    k = np.exp(-0.5 * (np.arange(-half, half + 1) / sd) ** 2)
    k /= k.sum()
    ext = np.vstack([tab[-half:], tab, tab[:half]])
    return np.stack([np.convolve(ext[:, c], k, "same")[half:-half] for c in range(3)], axis=1)


def rim_glow(rgb: np.ndarray, hard: np.ndarray, ball: tuple, fade_px: float,
             white: float = 254.0, band=(2.0, 9.0), warm_floor: float = -6.0,
             saturate: float = 1.2, smooth_deg: float = 20.0) -> np.ndarray:
    """用**球自己的边缘光**当底片：在球的边缘按方位取一条色环，再向外化白。

    和 andee-head 那条路（`glow_background`）要的是同一个东西：底片就是角色周围
    那圈光的延续，这样图标和封面看起来是同一张画。区别是源图自带 alpha 之后，
    球外面什么都没有可补 —— 能补的只有**球自己边上的那圈光**。

    五个必须做的判断：

    * **取样带必须贴边**（默认 R−2 到 R−9）。边缘光是**很薄的一层**：沿半径实测
      175° 是 −2:(246,174,144) 暖橙 → −5:(255,252,238) 白高光 → −12:(251,227,233)。
      取到 −14 就把球身内侧的白高光一起平均进来，底片会淡成一层洗不掉的灰粉。
    * **被挡住方位不采信。** 头顶的角、球底的手压在球的边缘上，那些方位的
      "球边缘像素"其实是角/手的颜色。判据是"这条射线上的剪影外沿是不是就落在
      球边附近"，不是就跳过，缺口按方位插值补。
    * **只要暖的那半边。** 球顶 60–120° 的边缘实测是 (174,211,250) 一类浅蓝 ——
      那是**顶光打在球面上的高光**，不是角色周围的那圈暖光。照单全收球顶会顶出
      一圈冷蓝，整个图标发灰。判据用 R−B（暖为正）：暖样本在 −4 以上、冷样本在
      −28 以下，取 −6；深蓝的描边（36,79,151）也被它滤掉。
    * **方位上要抹得很平。** 边缘光是**贴在球面上**的，相邻方位的色差会被径向拉
      成从中心甩出来的放射条纹（"色轮"）。±6° 只够去掉插值接缝，实测要
      sigma≈20° 才看不出条纹。代价是幅值也被平滑吃掉一部分（实测方位均值
      只剩 (238,202,209)，比旧底片的 (253,192,188) 淡），所以再用 `saturate`
      放大回去 —— 1.2 时球边上是 (234,196,198)，和旧图标是同一档浓度。
    * **化白长度按画布比例给**，不是按源图像素给 —— fade_px 由调用方按
      `比例 × 画布 / scale` 算出来，换个源图分辨率不用改这里。
    """
    H, W = hard.shape
    yy, xx = np.mgrid[0:H, 0:W]
    dx, dy = xx - ball[0], yy - ball[1]
    r = np.hypot(dx, dy)
    th = (np.degrees(np.arctan2(-dy, dx)) + 360.0) % 360.0
    idx = np.floor(th).astype(int) % 360

    outside = r > ball[2] + 6.0
    rim_outer = np.full(360, -1.0)
    hit = hard & outside
    for t in range(360):
        sel = hit & (idx == t)
        if sel.any():
            rim_outer[t] = r[sel].max()

    lo, hi = ball[2] - band[1], ball[2] - band[0]
    annular = hard & (r >= lo) & (r <= hi)
    tab = np.zeros((360, 3))
    have = np.zeros(360, bool)
    for t in range(360):
        if rim_outer[t] > 0:          # 球外还有东西 ⇒ 球边被挡住，不采信
            continue
        sel = annular & (idx == t)
        if sel.sum() >= 3:
            cols = rgb[sel]
            warm = cols[(cols[:, 0] - cols[:, 2]) >= warm_floor]
            if len(warm) >= 3:
                tab[t] = np.median(warm, axis=0)
                have[t] = True
    if have.sum() < 120:
        raise SystemExit(f"球的边缘光只采到 {int(have.sum())}/360 个方位，先看 ball_fit。")

    filled = tab.copy()
    for t in range(360):
        if have[t]:
            continue
        for k in range(1, 180):
            a, b = (t - k) % 360, (t + k) % 360
            if have[a] and have[b]:
                filled[t] = (tab[a] + tab[b]) / 2.0
                break
            if have[a]:
                filled[t] = tab[a]
                break
            if have[b]:
                filled[t] = tab[b]
                break

    smooth = _circ_smooth(filled, smooth_deg)
    edge = white + (smooth - white) * saturate

    d = np.clip(r - ball[2], 0.0, None)
    t = np.clip(d / fade_px, 0.0, 1.0)
    t = t * t * (3.0 - 2.0 * t)        # smoothstep：贴球处最浓，往外很快化白
    return np.clip(edge[idx] * (1.0 - t[..., None]) + white * t[..., None], 0, 255)


def components(mask: np.ndarray, min_px: int = 1):
    """4 邻域连通域，返回 [[(y,x), ...], ...]。不引 scipy —— 这个仓库的图片工具链
    只依赖 numpy + PIL。"""
    H, W = mask.shape
    seen = np.zeros((H, W), bool)
    out = []
    ys, xs = np.where(mask)
    for y0, x0 in zip(ys, xs):
        if seen[y0, x0]:
            continue
        seen[y0, x0] = True
        stack = [(y0, x0)]
        px = []
        while stack:
            y, x = stack.pop()
            px.append((y, x))
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < H and 0 <= nx < W and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    stack.append((ny, nx))
        if len(px) >= min_px:
            out.append(px)
    return out


def holes_from_dark(lum: np.ndarray, subject: np.ndarray, thr: float,
                    min_eye: int = 1500, min_mouth: int = 300, grow: float = 1.04):
    """五官镂空，给单色（主题）图标用。按**连通域大小**分，不按坐标分。

    阈值必须落在实测的亮度谷底里：think.webp 的主体亮度 [40,70) 是谷（每格
    275–393px），球身从 80 起堆量。取 55。

    分法是量出来的：眼睛 3724 / 3481px，嘴 498px，其余 115 个连通域全都 < 30px
    （角的描边、手的指缝）。中间是数量级的空档，所以"最大的两个 = 眼睛、剩下的
    最大 = 嘴"是稳的 —— 换成"y 坐标在某个范围"就会换个姿势就崩。

    眼睛用 bbox 拟合椭圆（眼球里的高光点比周围亮，阈值 mask 会在洞里留一个疙瘩），
    嘴用真实暗像素做闭运算（嘴是两端收细的弧，椭圆会把球身一起拖进来）。
    """
    H, W = lum.shape
    yy, xx = np.mgrid[0:H, 0:W]
    dark = subject & (lum < thr)
    cs = components(dark)
    cs.sort(key=len, reverse=True)
    eyes = [c for c in cs if len(c) >= min_eye]
    if len(eyes) != 2:
        raise SystemExit(f"眼睛找到 {len(eyes)} 个（阈值 {thr}），先量亮度直方图的谷底。")
    rest = [c for c in cs if min_mouth <= len(c) < min_eye]
    if len(rest) != 1:
        raise SystemExit(f"嘴找到 {len(rest)} 个：先看连通域大小的空档，再调 min_mouth。")
    mouth_px = rest[0]

    holes = np.zeros((H, W), bool)
    boxes = []
    for px in eyes:
        ys = [p[0] for p in px]
        xs = [p[1] for p in px]
        x0, x1 = np.percentile(xs, [0.5, 99.5])
        y0, y1 = np.percentile(ys, [0.5, 99.5])
        ex, ey = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        ax, ay = (x1 - x0 + 1) / 2.0 * grow, (y1 - y0 + 1) / 2.0 * grow
        holes |= ((xx - ex) / ax) ** 2 + ((yy - ey) / ay) ** 2 <= 1.0
        boxes.append((x0, y0, x1, y1))

    mouth = np.zeros((H, W), bool)
    for y, x in mouth_px:
        mouth[y, x] = True
    for _ in range(2):
        mouth = mouth | np.roll(mouth, 1, 0) | np.roll(mouth, -1, 0) | \
            np.roll(mouth, 1, 1) | np.roll(mouth, -1, 1)
    for _ in range(2):
        mouth = mouth & np.roll(mouth, 1, 0) & np.roll(mouth, -1, 0) & \
            np.roll(mouth, 1, 1) & np.roll(mouth, -1, 1)
    holes |= mouth & subject
    return holes, boxes, mouth


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
