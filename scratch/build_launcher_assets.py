import os
import math
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

# Ensure scratch exists
os.makedirs("scratch", exist_ok=True)

print("Starting Jarvis Launcher Asset Generation...")

# 1. Load source assets
uploaded_path = r"C:\Users\Antplay\.gemini\antigravity-ide\brain\3e7faa79-3d4c-454a-a101-bdb5979865ed\.user_uploaded\media_1792090965907.jpg"
orig_path = r"app\src\main\res\drawable\logo_jarvis_original.png"

up_img = Image.open(uploaded_path).convert("RGBA")
orig_img = Image.open(orig_path).convert("RGBA")

# 2. Extract high-resolution HUD from uploaded reference image
# In UP: circle center is (516, 501), radius of outer HUD brackets is ~468px
cx_up, cy_up = 516, 501
r_hud = 468
d_hud = r_hud * 2 # 936px

mask_hud = Image.new("L", (d_hud, d_hud), 0)
ImageDraw.Draw(mask_hud).ellipse((0, 0, d_hud, d_hud), fill=255)

hud_raw = up_img.crop((cx_up - r_hud, cy_up - r_hud, cx_up + r_hud, cy_up + r_hud))
hud_raw.putalpha(mask_hud)

# 3. Align central P emblem precisely to (468.0, 468.0)
# Measure current P stroke center:
arr_hud = np.array(hud_raw)
sub = arr_hud[350:580, 350:580]
p_stroke = (sub[:, :, 0] > 180) & (sub[:, :, 1] > 230) & (sub[:, :, 2] > 230)
ys, xs = np.where(p_stroke)
raw_p_mid_x = (xs.min() + xs.max()) / 2.0 + 350
raw_p_mid_y = (ys.min() + ys.max()) / 2.0 + 350
print(f"Raw P center in 936x936: ({raw_p_mid_x:.1f}, {raw_p_mid_y:.1f})")

# Calculate exact shifts
shift_x = -7
shift_y = -12
print(f"Applying shift: dx={shift_x}, dy={shift_y}")

# Blend P emblem region smoothly
y_grid, x_grid = np.ogrid[:d_hud, :d_hud]
dist_p = np.sqrt((x_grid - raw_p_mid_x)**2 + (y_grid - raw_p_mid_y)**2)
blend = np.clip((145.0 - dist_p) / 20.0, 0.0, 1.0)
blend = 0.5 - 0.5 * np.cos(blend * np.pi)

p_arr = np.array(hud_raw)
p_arr[:, :, 3] = (p_arr[:, :, 3].astype(float) * blend).astype(np.uint8)
p_layer = Image.fromarray(p_arr)

bg_arr = np.array(hud_raw)
bg_arr[:, :, 3] = (bg_arr[:, :, 3].astype(float) * (1.0 - blend)).astype(np.uint8)
bg_layer = Image.fromarray(bg_arr)

p_shifted = Image.new("RGBA", (d_hud, d_hud), (0, 0, 0, 0))
p_shifted.paste(p_layer, (shift_x, shift_y))

aligned_hud = Image.alpha_composite(bg_layer, p_shifted)
aligned_hud.save("scratch/aligned_hud_final.png")

# Verify P alignment
c_arr = np.array(aligned_hud)
crop_p = c_arr[320:620, 320:620]
p_stroke_v = (crop_p[:, :, 0] > 180) & (crop_p[:, :, 1] > 230) & (crop_p[:, :, 2] > 230)
ys_v, xs_v = np.where(p_stroke_v)
p_mid_x = (xs_v.min() + xs_v.max()) / 2.0 + 320
p_mid_y = (ys_v.min() + ys_v.max()) / 2.0 + 320
print(f"Verified Aligned P X: [{xs_v.min()+320}, {xs_v.max()+320}], midpoint: {p_mid_x:.1f}")
print(f"Verified Aligned P Y: [{ys_v.min()+320}, {ys_v.max()+320}], midpoint: {p_mid_y:.1f}")
assert abs(p_mid_x - 468.0) < 0.5, "P center X mismatch!"
assert abs(p_mid_y - 468.0) < 0.5, "P center Y mismatch!"

# 4. Create Master 1024x1024 Artwork
# Background from orig (full-bleed dark navy honeycomb)
master_bg = orig_img.convert("RGBA").resize((1024, 1024), Image.Resampling.LANCZOS)
master_icon = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
master_icon.paste(master_bg, (0, 0))
# Paste aligned HUD centered at (512, 512): offset = (512 - 468, 512 - 468) = (44, 44)
master_icon.paste(aligned_hud, (44, 44), aligned_hud)
master_icon.save("scratch/jarvis_launcher_master_1024.png")
master_icon.save(r"app\src\main\res\drawable\ic_jarvis_launcher_master.png")
print("Saved 1024x1024 master icon.")

# 5. Densities dictionary
# density: (legacy_size, adaptive_size)
densities = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

# 6. Generate Adaptive Backgrounds and Foregrounds
# In adaptive icon (canvas 108dp):
# HUD diameter is 308px for xxxhdpi (432x432) -> 308/432 = 0.71296
for density, (legacy_sz, adapt_sz) in densities.items():
    res_dir = f"app/src/main/res/mipmap-{density}"
    os.makedirs(res_dir, exist_ok=True)
    
    # Adaptive Background (108dp full bleed)
    adapt_bg = master_bg.resize((adapt_sz, adapt_sz), Image.Resampling.LANCZOS)
    adapt_bg.save(f"{res_dir}/ic_launcher_background.png")
    
    # Adaptive Foreground (108dp transparent canvas, HUD centered inside safe zone)
    hud_sz = int(round(adapt_sz * (308.0 / 432.0)))
    # Ensure hud_sz is even for exact centering
    if hud_sz % 2 != 0:
        hud_sz += 1
    
    hud_scaled = aligned_hud.resize((hud_sz, hud_sz), Image.Resampling.LANCZOS)
    fg_canvas = Image.new("RGBA", (adapt_sz, adapt_sz), (0, 0, 0, 0))
    offset = ((adapt_sz - hud_sz) // 2, (adapt_sz - hud_sz) // 2)
    fg_canvas.paste(hud_scaled, offset, hud_scaled)
    fg_canvas.save(f"{res_dir}/ic_launcher_foreground.png")
    
    # Legacy Square Icon (ic_launcher.png)
    # Scaled directly from master 1024x1024
    legacy_square = master_icon.resize((legacy_sz, legacy_sz), Image.Resampling.LANCZOS)
    legacy_square.save(f"{res_dir}/ic_launcher.png")
    
    # Legacy Round Icon (ic_launcher_round.png)
    round_mask = Image.new("L", (legacy_sz * 4, legacy_sz * 4), 0)
    ImageDraw.Draw(round_mask).ellipse((0, 0, legacy_sz * 4, legacy_sz * 4), fill=255)
    round_mask = round_mask.resize((legacy_sz, legacy_sz), Image.Resampling.LANCZOS)
    
    legacy_round = Image.new("RGBA", (legacy_sz, legacy_sz), (0, 0, 0, 0))
    legacy_round.paste(legacy_square, (0, 0), round_mask)
    legacy_round.save(f"{res_dir}/ic_launcher_round.png")
    
    # Themed Monochrome Raster Icon (ic_launcher_monochrome.png)
    # Clean white silhouette of the P emblem within the adaptive safe zone
    # In fg_canvas, extract the P emblem mask and color it pure white with alpha
    mono_canvas = Image.new("RGBA", (adapt_sz, adapt_sz), (0, 0, 0, 0))
    
    # High-res P silhouette in 936x936
    p_mono_936 = Image.new("RGBA", (d_hud, d_hud), (0, 0, 0, 0))
    # Threshold P stroke and interior fill in aligned_hud
    c_arr = np.array(aligned_hud)
    # P mask in central area
    y_m, x_m = np.ogrid[:d_hud, :d_hud]
    dist_m = np.sqrt((x_m - 468.0)**2 + (y_m - 468.0)**2)
    p_solid = (dist_m < 118) & ((c_arr[:, :, 2] > 180) | (c_arr[:, :, 1] > 180)) & (c_arr[:, :, 3] > 100)
    
    mono_arr = np.zeros((d_hud, d_hud, 4), dtype=np.uint8)
    mono_arr[p_solid] = [255, 255, 255, 255]
    p_mono_936 = Image.fromarray(mono_arr).filter(ImageFilter.SMOOTH)
    
    # Scale mono to hud_sz
    p_mono_scaled = p_mono_936.resize((hud_sz, hud_sz), Image.Resampling.LANCZOS)
    mono_canvas.paste(p_mono_scaled, offset, p_mono_scaled)
    mono_canvas.save(f"{res_dir}/ic_launcher_monochrome.png")
    
    print(f"Generated assets for mipmap-{density}: legacy={legacy_sz}x{legacy_sz}, adaptive={adapt_sz}x{adapt_sz}")

print("All raster mipmap densities generated successfully!")
