import os
from PIL import Image, ImageDraw, ImageFont
import numpy as np

os.makedirs("scratch", exist_ok=True)

# 1. Load assets
master = Image.open(r"app\src\main\res\drawable\ic_jarvis_launcher_master.png")
fg = Image.open(r"app\src\main\res\mipmap-xxxhdpi\ic_launcher_foreground.png")
bg = Image.open(r"app\src\main\res\mipmap-xxxhdpi\ic_launcher_background.png")
mono = Image.open(r"app\src\main\res\mipmap-xxxhdpi\ic_launcher_monochrome.png")

# 2. Composite 432x432
composite_432 = Image.alpha_composite(bg, fg)
center_288 = composite_432.crop((72, 72, 360, 360))

# 3. Create masks for 288x288 (72dp)
# Circle
m_circle = Image.new("L", (288, 288), 0)
ImageDraw.Draw(m_circle).ellipse((0, 0, 288, 288), fill=255)

# Squircle (Samsung / standard)
m_squircle = Image.new("L", (288, 288), 0)
ImageDraw.Draw(m_squircle).rounded_rectangle((0, 0, 288, 288), radius=58, fill=255)

# Rounded Square
m_roundsq = Image.new("L", (288, 288), 0)
ImageDraw.Draw(m_roundsq).rounded_rectangle((0, 0, 288, 288), radius=36, fill=255)

# Teardrop (Pixel teardrop)
m_teardrop = Image.new("L", (288, 288), 0)
td_draw = ImageDraw.Draw(m_teardrop)
td_draw.pieslice((0, 0, 288, 288), 0, 360, fill=255)
# corner at top right
td_draw.rectangle((144, 0, 288, 144), fill=255)
m_teardrop = Image.fromarray(np.maximum(np.array(m_circle), np.array(m_teardrop)))

def apply_mask(img_288, mask, bg_color=(24, 28, 36, 255)):
    out = Image.new("RGBA", (288, 288), bg_color)
    out.paste(img_288, (0, 0), mask)
    return out

v_circle = apply_mask(center_288, m_circle)
v_squircle = apply_mask(center_288, m_squircle)
v_roundsq = apply_mask(center_288, m_roundsq)
v_square = center_288

# 4. Monochrome themed icon previews
# Light theme: Background = #D1E4FF, Icon tint = #001D36
# Dark theme: Background = #004A77, Icon tint = #C2E7FF
def create_themed_preview(mono_img, mask, bg_hex, tint_hex):
    # bg
    th_bg = Image.new("RGBA", (288, 288), bg_hex)
    # tint mono
    mono_crop = mono_img.crop((72, 72, 360, 360))
    m_arr = np.array(mono_crop)
    alpha = m_arr[:, :, 3]
    
    tint_rgb = [int(tint_hex[i:i+2], 16) for i in (1, 3, 5)]
    tinted = np.zeros((288, 288, 4), dtype=np.uint8)
    tinted[:, :, :3] = tint_rgb
    tinted[:, :, 3] = alpha
    tinted_img = Image.fromarray(tinted)
    
    th_comp = Image.alpha_composite(th_bg, tinted_img)
    return apply_mask(th_comp, mask)

th_light = create_themed_preview(mono, m_squircle, (209, 228, 255, 255), "#001D36")
th_dark = create_themed_preview(mono, m_circle, (0, 74, 119, 255), "#C2E7FF")

# 5. Build presentation sheet
sheet_w = 1200
sheet_h = 1000
sheet = Image.new("RGB", (sheet_w, sheet_h), (13, 17, 23))
draw = ImageDraw.Draw(sheet)

# Draw Master Preview (left column)
# Resize master to 360x360
master_thumb = master.resize((360, 360), Image.Resampling.LANCZOS)
sheet.paste(master_thumb.convert("RGB"), (40, 80))

# Label Master
draw.text((40, 50), "MASTER ARTWORK (1024x1024 Square)", fill=(200, 220, 255))
draw.text((40, 450), "P Centering: X=512.0, Y=512.0 (Exact 1:1)", fill=(120, 180, 240))
draw.text((40, 470), "Concentric Rings: Center (512.0, 512.0)", fill=(120, 180, 240))
draw.text((40, 490), "Background: Full-bleed dark navy honeycomb", fill=(120, 180, 240))

# Adaptive mask previews (middle & right columns)
# 4 masks at 144x144
previews = [
    ("Pixel Circle (72dp)", v_circle.resize((150, 150), Image.Resampling.LANCZOS)),
    ("Samsung Squircle (72dp)", v_squircle.resize((150, 150), Image.Resampling.LANCZOS)),
    ("Rounded Square (72dp)", v_roundsq.resize((150, 150), Image.Resampling.LANCZOS)),
    ("Full Square View", v_square.resize((150, 150), Image.Resampling.LANCZOS)),
    ("Themed Dark Mode (API 33+)", th_dark.resize((150, 150), Image.Resampling.LANCZOS)),
    ("Themed Light Mode (API 33+)", th_light.resize((150, 150), Image.Resampling.LANCZOS)),
]

draw.text((450, 50), "ANDROID ADAPTIVE ICON MASK PREVIEWS (72dp Viewport)", fill=(200, 220, 255))

for idx, (label, img) in enumerate(previews):
    r = idx // 3
    c = idx % 3
    x = 450 + c * 240
    y = 90 + r * 200
    sheet.paste(img.convert("RGB"), (x, y))
    draw.text((x, y + 160), label, fill=(160, 190, 220))

# Bottom row: Legacy icons at native sizes
draw.text((40, 560), "LEGACY LAUNCHER ICONS (Square & Circular Mipmaps)", fill=(200, 220, 255))
x_cur = 40
for d_name, sz in [("xxxhdpi", 192), ("xxhdpi", 144), ("xhdpi", 96), ("hdpi", 72), ("mdpi", 48)]:
    sq_ic = Image.open(f"app/src/main/res/mipmap-{d_name}/ic_launcher.png")
    rd_ic = Image.open(f"app/src/main/res/mipmap-{d_name}/ic_launcher_round.png")
    
    sheet.paste(sq_ic.convert("RGB"), (x_cur, 600))
    # Paste round with alpha over dark bg
    sheet.paste(rd_ic, (x_cur, 810), rd_ic)
    draw.text((x_cur, 580), f"{d_name} ({sz}px)", fill=(140, 160, 190))
    x_cur += sz + 30

draw.text((40, 790), "Round Mipmaps (Anti-aliased circle):", fill=(160, 180, 200))

sheet.save("scratch/launcher_verification_sheet.png")
print("Saved scratch/launcher_verification_sheet.png successfully!")
