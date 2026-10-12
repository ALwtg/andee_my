#!/usr/bin/env python3
"""从 images/brand/andee-head.png 生成整套 Android 启动图标。

源图是不透明的（白底 + 橙粉光晕 + 蓝色头部），抠图与五官遮罩在
`andee_head.py`；这里只管"怎么摆到各个图层里"。

## 四种图

  * `ic_launcher_foreground`  自适应前景：抠出的角色，透明底
  * `ic_launcher_background`  自适应背景：**源图自己的光晕**，头部那块用周围的
    光晕补平（push-pull 式的归一化卷积）。不是另画一张渐变 —— 图标和封面要
    看起来是同一张画，所以用同一份光晕
  * `ic_launcher_monochrome`  主题图标：黑 = 实体，透明 = 镂空，眼睛和嘴是洞
  * `ic_launcher` / `_round`  传统光栅图（API < 26 与部分启动器）：背景 + 角色
    合成，圆形版加圆遮罩

## 几何

**一次变换，不是每层各缩一次。** 所有层从同一张源图、同一个 scale、同一组整数
offset 变换过去 —— 每层各缩一次会让单色层的眼睛洞和前景的眼睛差 1px，432px
上看不出，108px 上一眼就歪。

**外接圆决定大小。** 系统把画布中央 72/108 当保证可见的圆。角伸在头外，外接圆
比头大；把角色整体下移到"到画布中心的最大距离"最小，再把这个距离缩到可见圆半径
的 `CONTENT` 倍。角永远不会被圆形遮罩切到。

传统图单独构建（同样按外接圆，占圆形的 `LEGACY_CONTENT`），不从自适应画布缩下
来 —— 那样会把为遮罩预留的那圈死边一起缩进去，48px 上就是一张贴纸。

用法:
  python3 tools/make_launcher_icon.py                  # 写 app/src/main/res/mipmap-*
  python3 tools/make_launcher_icon.py --dump /tmp/x    # 另存背景 / 前景大图核对
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
import andee_head as head  # noqa: E402

DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]
ADAPTIVE_DP = 108.0
LEGACY_DP = 48.0
VISIBLE_FRACTION = 72.0 / 108.0

# 角色外接圆半径 / 可见圆半径。小于 1 是给光晕留出看得见的一圈 —— 角色占满可见圆
# 的话，背景只剩一道细边，等于白做。
CONTENT = 0.86
# 传统图：外接圆半径 / （方图边长的一半）。圆形版的圆就是这一半，所以 < 1 才保证
# 角不被切。
LEGACY_CONTENT = 0.90

SHARPEN_PERCENT = 40


# ---------------------------------------------------------------- 背景
def gauss_fft(field: np.ndarray, sigma: float) -> np.ndarray:
    """频域高斯模糊。PIL 的 GaussianBlur 不支持浮点图，scipy 这里没有。"""
    H, W = field.shape
    fy = np.fft.fftfreq(H)[:, None]
    fx = np.fft.fftfreq(W)[None, :]
    k = np.exp(-2.0 * (np.pi ** 2) * (sigma ** 2) * (fx ** 2 + fy ** 2))
    return np.real(np.fft.ifft2(np.fft.fft2(field) * k))


def glow_background(a: np.ndarray, hard: np.ndarray) -> np.ndarray:
    """把源图里头部那块补成周围光晕的延伸，得到一张只有光晕的底片。

    光晕是以头部为中心向外的径向渐变，所以补法是**按距离取环**：对头部之外的
    每个半径，取该半径上光晕像素的平均色，再让头部之内的像素按到头部中心的
    距离去查这张"半径→颜色"表。内部的半径超出已知范围时，沿用最内一环的颜色。
    再整体做一次大半径模糊压掉环状噪声 —— 头部被角色盖住，补出来的内容只在
    系统给图层做视差位移时才会露一点边，要的是平滑，不是细节。

    第一版用的是"归一化卷积"（已知像素带权重向未知区域扩散）：头部这么大一块
    洞，它在洞中央只剩一个很小的权重，补出来是一个白斑加一道头部轮廓的影子。
    """
    H, W = hard.shape
    ys, xs = np.where(hard)
    cy, cx = ys.mean(), xs.mean()
    yy, xx = np.mgrid[0:H, 0:W]
    dist = np.hypot(xx - cx, yy - cy)

    near = hard.copy()
    for _ in range(8):
        near = near | np.roll(near, 1, 0) | np.roll(near, -1, 0) | np.roll(near, 1, 1) | np.roll(near, -1, 1)
    known = ~near

    # 头部外沿最远的半径之外才是"光晕环"可用的范围；只取到画布内接圆，避开
    # 四个角上的纯白。
    rmax = min(H, W) / 2.0
    edges = np.arange(0.0, rmax + 1.0, 2.0)
    idx = np.digitize(dist, edges) - 1
    nb = len(edges) - 1
    table = np.zeros((nb, 3))
    have = np.zeros(nb, bool)
    for c in range(3):
        s = np.bincount(idx[known & (idx < nb)], weights=a[..., c][known & (idx < nb)], minlength=nb)[:nb]
        n = np.bincount(idx[known & (idx < nb)], minlength=nb)[:nb]
        ok = n > 40
        table[ok, c] = s[ok] / n[ok]
        have |= ok
    first = int(np.argmax(have))
    for i in range(first):
        table[i] = table[first]
    for i in range(first, nb):
        if not have[i]:
            table[i] = table[i - 1]

    out = a.copy()
    inner = near & (idx >= 0) & (idx < nb)
    out[inner] = table[idx[inner]]
    # 径向表在方位上是平均的，会丢掉"左橙右粉"的方位差；再混回一点原有光晕的
    # 方位信息：用已知像素相对表值的残差做一个很大的模糊后加回去。
    res = np.where(known[..., None], a - table[np.clip(idx, 0, nb - 1)], 0.0)
    w = gauss_fft(known.astype(np.float64), 120.0)
    for c in range(3):
        r = gauss_fft(res[..., c], 120.0)
        out[..., c][near] += (r / np.maximum(w, 0.03))[near]
    for c in range(3):
        sm = gauss_fft(out[..., c], 18.0)
        out[..., c][near] = sm[near]
    return np.clip(out, 0, 255)



# ---------------------------------------------------------------- 工具
def resize_rgba(im: Image.Image, size: int) -> Image.Image:
    """缩放 RGBA，走预乘 —— 否则透明像素的黑会渗进半透明边缘变成暗边。"""
    a = np.asarray(im).astype(np.float64)
    al = a[:, :, 3:4] / 255.0
    pm = Image.fromarray(np.dstack([a[:, :, :3] * al, a[:, :, 3]]).astype(np.uint8), "RGBA")
    b = np.asarray(pm.resize((size, size), Image.LANCZOS)).astype(np.float64)
    al2 = b[:, :, 3:4] / 255.0
    rgb = np.where(al2 > 1e-6, b[:, :, :3] / np.maximum(al2, 1e-6), 0.0)
    return Image.fromarray(np.dstack([np.clip(rgb, 0, 255), b[:, :, 3]]).astype(np.uint8), "RGBA")


def sharpen_rgb(im: Image.Image) -> Image.Image:
    """只锐化 RGB，绝不碰 alpha —— 碰 alpha 会把剪影弄毛。"""
    r, g, b, a = im.split()
    rgb = Image.merge("RGB", (r, g, b)).filter(
        ImageFilter.UnsharpMask(radius=1.0, percent=SHARPEN_PERCENT, threshold=0))
    r2, g2, b2 = rgb.split()
    return Image.merge("RGBA", (r2, g2, b2, a))


def blit(dst: Image.Image, src: Image.Image, ox: int, oy: int):
    """贴图，负坐标也正确裁剪。所有层都必须走这一个函数，见文件头。"""
    dw, dh = dst.size
    sw, sh = src.size
    x0, y0 = max(0, ox), max(0, oy)
    x1, y1 = min(dw, ox + sw), min(dh, oy + sh)
    if x1 <= x0 or y1 <= y0:
        return
    patch = src.crop((x0 - ox, y0 - oy, x1 - ox, y1 - oy))
    dst.paste(patch, (x0, y0), patch if patch.mode == "RGBA" else None)


def webp(im: Image.Image, path: Path):
    im.save(path, "WEBP", lossless=True, method=6)


# ---------------------------------------------------------------- 主流程
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--res", default="app/src/main/res")
    ap.add_argument("--dump", default=None, help="把源分辨率的背景/前景/单色另存到该目录")
    ap.add_argument("--content", type=float, default=1.0,
                    help="角色大小因子，乘在 CONTENT / LEGACY_CONTENT 上")
    args = ap.parse_args()

    rgba, alpha, hard, a = head.character_rgba()
    H, W = alpha.shape
    ccx, ccy = (W - 1) / 2.0, (H - 1) / 2.0
    opt, dy = head.circumcircle(alpha)
    holes, boxes, _ = head.face_holes(a, hard)
    print(f"源图 {W}×{H}  外接圆半径 {opt:.1f}px，下移 {dy}px")

    char = Image.fromarray(rgba, "RGBA")
    bg_arr = glow_background(a, hard)
    bg = Image.fromarray(bg_arr.astype(np.uint8), "RGB")
    border = np.concatenate([bg_arr[0], bg_arr[-1], bg_arr[:, 0], bg_arr[:, -1]])
    pad = tuple(int(round(v)) for v in np.median(border, axis=0))

    mono = alpha.copy()
    mono[holes] = 0.0
    mono_img = Image.fromarray(np.clip(mono * 255.0, 0, 255).astype(np.uint8), "L")

    if args.dump:
        d = Path(args.dump)
        d.mkdir(parents=True, exist_ok=True)
        bg.save(d / "background.png")
        char.save(d / "foreground.png")
        mono_img.save(d / "monochrome.png")

    res = Path(args.res)
    content = CONTENT * args.content
    legacy_content = LEGACY_CONTENT * args.content
    total = 0
    for name, dpi in DENSITIES:
        out = res / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        cvs = int(round(ADAPTIVE_DP * dpi))
        lgs = int(round(LEGACY_DP * dpi))

        # ============ 自适应层 ============
        # 源图中心对齐画布中心再整体下移 dy。dy 要单独一项写：化简成
        # `cvs/2 - ccy*scale` 它就消失了，角会被顶出可见圆。
        vis_r = cvs * VISIBLE_FRACTION / 2.0
        scale = vis_r * content / opt
        n = max(8, int(round(W * scale)))
        off_x = int(round(cvs / 2.0 - ccx * scale))
        off_y = int(round(cvs / 2.0 - ccy * scale + dy * scale))

        fg = Image.new("RGBA", (cvs, cvs), (0, 0, 0, 0))
        blit(fg, resize_rgba(char, n), off_x, off_y)
        fg = sharpen_rgb(fg)
        webp(fg, out / "ic_launcher_foreground.webp")

        bgc = Image.new("RGB", (cvs, cvs), pad)
        blit(bgc, bg.resize((n, n), Image.LANCZOS), off_x, off_y)
        webp(bgc, out / "ic_launcher_background.webp")

        mono_c = Image.new("L", (cvs, cvs), 0)
        blit(mono_c, mono_img.resize((n, n), Image.BOX), off_x, off_y)
        mc = np.asarray(mono_c)
        webp(Image.fromarray(np.dstack([np.zeros_like(mc)] * 3 + [mc]), "RGBA"),
             out / "ic_launcher_monochrome.webp")

        # ============ 传统光栅（单独构建） ============
        lscale = (lgs / 2.0) * legacy_content / opt
        ln = max(8, int(round(W * lscale)))
        lx = int(round(lgs / 2.0 - ccx * lscale))
        ly = int(round(lgs / 2.0 - ccy * lscale + dy * lscale))
        flat = Image.new("RGB", (lgs, lgs), pad)
        blit(flat, bg.resize((ln, ln), Image.LANCZOS), lx, ly)
        lc = Image.new("RGBA", (lgs, lgs), (0, 0, 0, 0))
        blit(lc, resize_rgba(char, ln), lx, ly)
        lc = sharpen_rgb(lc)
        flat.paste(lc, (0, 0), lc)
        webp(flat, out / "ic_launcher.webp")

        yy, xx = np.mgrid[0:lgs, 0:lgs]
        cc = (lgs - 1) / 2.0
        circ = Image.fromarray(((np.hypot(xx - cc, yy - cc) <= cc + 0.5) * 255).astype(np.uint8), "L")
        rnd = Image.new("RGBA", (lgs, lgs), (0, 0, 0, 0))
        rnd.paste(flat, (0, 0), circ)
        webp(rnd, out / "ic_launcher_round.webp")

        total += sum((out / f).stat().st_size for f in (
            "ic_launcher.webp", "ic_launcher_round.webp", "ic_launcher_foreground.webp",
            "ic_launcher_background.webp", "ic_launcher_monochrome.webp"))
        print(f"  {name:7s} 画布 {cvs:3d} 传统 {lgs:3d}  角色外接圆半径 "
              f"{opt * scale:5.1f} / 可见 {vis_r:.0f}")
    print(f"写入 {5 * len(DENSITIES)} 个文件，共 {total / 1024:.0f}KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
