#!/usr/bin/env python3
"""Финальный алгоритм: замыкание разрыва + площадь по центральной линии контура."""
import sys, cv2, numpy as np, os

def detect(path, thin=True, dpi=200.0, min_area_cm=1.0):
    img = cv2.imread(path)
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    if thin:
        block = max(15, (g.shape[1] // 40) | 1)
        b = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, block, 10)
    else:
        _, b = cv2.threshold(g, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    b[:3, :] = 0; b[-3:, :] = 0; b[:, :3] = 0; b[:, -3:] = 0

    H, W = b.shape
    ppc2 = (dpi / 2.54) ** 2
    kmax = max(15, W // 6)
    found = []  # (cx, cy, area_cm)

    k = 3
    while k <= kmax:
        se = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
        closed = cv2.morphologyEx(b, cv2.MORPH_CLOSE, se)
        cnts, hier = cv2.findContours(closed, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
        if hier is not None:
            hier = hier[0]
            for i in range(len(cnts)):
                parent = hier[i][3]
                if parent == -1:
                    continue  # не дырка
                inner = cv2.contourArea(cnts[i])
                if inner < 50:
                    continue
                outer = cv2.contourArea(cnts[int(parent)])
                area = (inner + outer) / 2.0
                area_cm = area / ppc2
                if area_cm < min_area_cm:
                    continue
                M = cv2.moments(cnts[i])
                cx, cy = M["m10"] / M["m00"], M["m01"] / M["m00"]
                # уже найдено рядом?
                if any((cx - fx) ** 2 + (cy - fy) ** 2 < (W * 0.05) ** 2 for fx, fy, _ in found):
                    continue
                found.append((cx, cy, area_cm))
                print(f"  [k={k}] фигура: {area_cm:.2f} см² (внеш {outer/ppc2:.2f} / внутр {inner/ppc2:.2f})")
        k += 4

    print(f"\nИТОГО: {len(found)} фигур")
    out = img.copy()
    for cx, cy, a in found:
        cv2.putText(out, f"{a:.1f}", (int(cx), int(cy)), cv2.FONT_HERSHEY_SIMPLEX, max(1, W/900), (0, 0, 255), 2)
    cv2.imwrite(os.path.splitext(path)[0] + "_result.png", out)
    return found

if __name__ == "__main__":
    detect(sys.argv[1], thin="--thin" in sys.argv or True,
           min_area_cm=float(sys.argv[sys.argv.index("--min")+1]) if "--min" in sys.argv else 1.0)
