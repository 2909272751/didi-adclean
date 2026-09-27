"""自绘模块图标：滴滴橙圆角底 + 白色小车 + “禁止广告”红圈斜杠。无第三方素材。"""
from PIL import Image, ImageDraw

S = 512
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

# ── 圆角底：滴滴橙渐变（#FFA53C → #FF6A00）─────────────────────────────
bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
grad = Image.new("RGBA", (S, S))
gd = grad.load()
for y in range(S):
    t = y / (S - 1)
    r = int(255 * (1 - t) + 255 * t)
    g = int(165 * (1 - t) + 106 * t)
    b = int(60 * (1 - t) + 0 * t)
    for x in range(S):
        gd[x, y] = (r, g, b, 255)
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=118, fill=255)
bg.paste(grad, (0, 0), mask)
img.alpha_composite(bg)

d = ImageDraw.Draw(img)
WHITE = (255, 255, 255, 255)
GLASS = (255, 226, 190, 255)
TIRE = (58, 58, 64, 255)
RED = (226, 54, 54, 255)

# ── 车身（侧视）───────────────────────────────────────────────────────
d.rounded_rectangle([104, 236, 404, 330], radius=30, fill=WHITE)          # 车身
d.polygon([(168, 242), (212, 174), (302, 174), (346, 242)], fill=WHITE)  # 车顶
d.polygon([(192, 234), (222, 190), (292, 190), (322, 234)], fill=GLASS)  # 车窗
d.rounded_rectangle([104, 274, 404, 304], radius=12, fill=(255, 240, 224, 255))  # 腰线

# ── 车轮 ──────────────────────────────────────────────────────────────
for cx in (176, 332):
    d.ellipse([cx - 38, 292, cx + 38, 368], fill=TIRE)
    d.ellipse([cx - 14, 316, cx + 14, 344], fill=(210, 214, 219, 255))

# ── “禁止广告”标记（右下角红圈 + 白斜杠）──────────────────────────────
bcx, bcy, br = 398, 392, 76
d.ellipse([bcx - br, bcy - br, bcx + br, bcy + br], fill=RED, outline=WHITE, width=10)
d.line([(bcx - 47, bcy - 47), (bcx + 47, bcy + 47)], fill=WHITE, width=22)

img.save(r"C:\实用软件开发\didi-adclean\assets\icon-512.png")
img.resize((256, 256), Image.LANCZOS).save(
    r"C:\实用软件开发\didi-adclean\app\res\drawable-nodpi\ic_launcher.png", optimize=True)
img.resize((192, 192), Image.LANCZOS).save(r"C:\实用软件开发\didi-lsposed\recon\icon-192.png", optimize=True)
print("icon written")
