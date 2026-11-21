#!/usr/bin/env python3
"""Synthetic test-only sheets; needs Pillow and qrcode==8.2 (not app dependencies).

Geometry and mark sequence follow native/omr_engine/tests/R11PackagedRegression.cpp.
Each question q selects (q-1)%4+1. T3 is rotated 180°.
These generated images are packaged only in the instrumentation APK.
"""
from pathlib import Path
import json
from PIL import Image, ImageDraw
import qrcode

root = Path(__file__).resolve().parents[1]
output = root / "app/src/androidTest/assets/omr"
output.mkdir(parents=True, exist_ok=True)
templates = ["yse_omr_a5_t1_100q_v2.json", "yse_omr_a5_t3_40q_v1.json"]
for index, name in enumerate(templates):
    data = json.loads((root / "app/src/main/assets/templates" / name).read_text())
    size = data["coordinateSystem"]["referenceRender"]
    scale = 2
    image = Image.new("RGB", (size["width"] * scale, size["height"] * scale), (245, 245, 245))
    draw = ImageDraw.Draw(image)

    def circle(x, y, radius, fill=None, outline=None, width=1):
        box = tuple(round(v * scale) for v in (x-radius, y-radius, x+radius, y+radius))
        draw.ellipse(box, fill=fill, outline=outline, width=width * scale)

    rail = data["liveDetection"]["registration"]
    for side in ("left", "right"):
        for y in rail.get(f"{side}RowY", rail["rowY"]):
            circle(rail[f"{side}RailX"], y, rail["dotRadius"], fill=(0, 0, 0))
    for block in data["answers"]["blocks"]:
        for row, y in enumerate(block["yByRow"]):
            question = block["questionStart"] + row
            for option, x in block["xByOption"].items():
                circle(x, y, block["bubbleRadius"], outline=(182, 3, 75), width=2)
                if int(option) == (question-1) % 4 + 1:
                    circle(x, y, block["bubbleRadius"] * .70, fill=(24, 24, 24))
    image = image.resize((size["width"], size["height"]), Image.Resampling.LANCZOS)
    code = data["sheetType"]["code"]
    payload = "T3:213327:rZeGOHtv" if code == "T3" else "T1:001234:S2000000000000000000"
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_Q, box_size=1, border=4)
    qr.add_data(payload); qr.make(fit=True)
    code = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    roi = data["examCode"]["qrCode"]["roi"]
    width, height = round(roi["width"]), round(roi["height"])
    zoom = min(width, height) // code.width
    code = code.resize((code.width * zoom, code.height * zoom), Image.Resampling.NEAREST)
    panel = Image.new("RGB", (width, height), "white")
    panel.paste(code, ((width-code.width)//2, (height-code.height)//2))
    image.paste(panel, (round(roi["x"]), round(roi["y"])))
    if code == "T3":
        image = image.transpose(Image.Transpose.ROTATE_180)
    image.save(output / f"{code}.jpg", quality=94)
    print(f"{code}: {data['answers']['questionCount']} known answers; {payload}")
