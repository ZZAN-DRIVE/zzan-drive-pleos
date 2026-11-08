#!/usr/bin/env python3
"""시험용 임시 미디어를 만든다 (실제 캐릭터 아님).

기획서는 "배경 없이 캐릭터와 음식만으로" 알아볼 수 있어야 한다고 했으므로
투명 배경(알파) 이미지와 투명 애니메이션이 에뮬레이터에서 제대로 표시되는지 보는 용도이다.
출력: tools/sdk-probe/app/src/main/assets/media/ (git에 올라가지 않음)
"""
import math
import pathlib
from PIL import Image, ImageDraw

OUT = pathlib.Path(__file__).resolve().parent.parent / "tools/sdk-probe/app/src/main/assets/media"
OUT.mkdir(parents=True, exist_ok=True)
W = H = 480


def frame(t, n):
    """t=0..n-1 : 캐릭터가 삼각김밥을 한 입씩 먹는 12프레임짜리 임시 그림."""
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))  # 완전 투명 배경
    d = ImageDraw.Draw(im)
    # 몸통
    d.ellipse((120, 150, 360, 440), fill=(255, 205, 90, 255), outline=(60, 40, 10, 255), width=6)
    # 눈
    blink = (t % 6 == 5)
    for ex in (195, 285):
        if blink:
            d.line((ex - 14, 235, ex + 14, 235), fill=(30, 20, 10, 255), width=6)
        else:
            d.ellipse((ex - 12, 220, ex + 12, 250), fill=(30, 20, 10, 255))
    # 입: 여닫기
    open_amt = abs(math.sin(math.pi * t / (n / 3)))
    my = 290
    d.ellipse((205, my, 275, my + 14 + int(46 * open_amt)), fill=(150, 40, 40, 255))
    # 삼각김밥 (한 입씩 줄어듦)
    scale = 1.0 - 0.7 * (t / (n - 1))
    cx, cy = 240, 105
    s = 70 * scale
    d.polygon([(cx, cy - s), (cx - s, cy + s * 0.7), (cx + s, cy + s * 0.7)], fill=(250, 250, 250, 255), outline=(40, 40, 40, 255))
    d.rectangle((cx - s * 0.45, cy + s * 0.05, cx + s * 0.45, cy + s * 0.7), fill=(20, 40, 30, 255))
    # 반투명 그림자(알파 중간값 확인용)
    d.ellipse((130, 430, 350, 470), fill=(0, 0, 0, 90))
    return im


N = 12
frames = [frame(i, N) for i in range(N)]
frames[0].save(OUT / "char_static.png")
frames[0].save(OUT / "char_eating.webp", save_all=True, append_images=frames[1:], duration=120, loop=0, lossless=True)
# GIF는 알파가 1비트뿐이라 가장자리가 거칠다 (비교용)
frames[0].save(OUT / "char_eating.gif", save_all=True, append_images=frames[1:], duration=120, loop=0, disposal=2, transparency=0)
# ---- 형식 비교용 추가 에셋 --------------------------------------------------------
frames[0].convert("RGB").save(OUT / "char_static_opaque.jpg", quality=90)          # JPEG: 알파 없음
frames[0].convert("RGB").save(OUT / "char_static_lossy.webp", quality=80)          # WebP 손실, 알파 없음
frames[0].save(OUT / "char_static_alpha_lossy.webp", quality=80)                   # WebP 손실 + 알파
frames[0].convert("RGB").save(OUT / "char_static_opaque.bmp")                      # BMP
frames[0].save(OUT / "char_eating_lossy_alpha.webp", save_all=True, append_images=frames[1:],
               duration=120, loop=0, lossless=False, quality=75)                   # 애니메이션 WebP 손실 + 알파
frames[0].save(OUT / "char_eating.apng", format="PNG", save_all=True, append_images=frames[1:],
               duration=120, loop=0)                                               # APNG (확장자 .apng)

# 무거운 애니메이션: 1024x1024, 40프레임 (디코드 시간·용량 확인용)
def big_frame(t, n):
    base = frame(t % 12, 12).resize((1024, 1024), Image.LANCZOS)
    return base
big = [big_frame(i, 40) for i in range(40)]
big[0].save(OUT / "heavy_1024_40f.webp", save_all=True, append_images=big[1:], duration=60, loop=0, lossless=True)

# Lottie(벡터 애니메이션) 최소 예제: 색 원이 커졌다 작아지는 2초짜리
import json
lottie = {"v": "5.7.0", "fr": 30, "ip": 0, "op": 60, "w": 400, "h": 400, "nm": "zzan", "ddd": 0, "assets": [],
  "layers": [{"ddd": 0, "ind": 1, "ty": 4, "nm": "circle", "sr": 1,
    "ks": {"o": {"a": 0, "k": 100}, "r": {"a": 0, "k": 0}, "p": {"a": 0, "k": [200, 200, 0]}, "a": {"a": 0, "k": [0, 0, 0]},
           "s": {"a": 1, "k": [
             {"t": 0, "s": [60, 60, 100], "e": [120, 120, 100], "i": {"x": [0.5], "y": [1]}, "o": {"x": [0.5], "y": [0]}},
             {"t": 30, "s": [120, 120, 100], "e": [60, 60, 100], "i": {"x": [0.5], "y": [1]}, "o": {"x": [0.5], "y": [0]}},
             {"t": 60}]}},
    "ao": 0,
    "shapes": [{"ty": "gr", "it": [
      {"ty": "el", "p": {"a": 0, "k": [0, 0]}, "s": {"a": 0, "k": [200, 200]}, "nm": "e"},
      {"ty": "fl", "c": {"a": 0, "k": [1, 0.8, 0.3, 1]}, "o": {"a": 0, "k": 100}, "r": 1, "nm": "f"},
      {"ty": "tr", "p": {"a": 0, "k": [0, 0]}, "a": {"a": 0, "k": [0, 0]}, "s": {"a": 0, "k": [100, 100]}, "r": {"a": 0, "k": 0}, "o": {"a": 0, "k": 100}}],
      "nm": "g"}],
    "ip": 0, "op": 60, "st": 0, "bm": 0}], "markers": []}
(OUT / "char_lottie.json").write_text(json.dumps(lottie), encoding="utf-8")

for p in sorted(OUT.glob("char_*")) + sorted(OUT.glob("heavy_*")):
    print(f"{p.name:20s} {p.stat().st_size:>8,d} bytes")
