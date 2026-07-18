#!/usr/bin/env python3
"""
Локальный тест алгоритма подсчёта площади (повторяет AreaProcessor.kt).
Запуск:  python3 test_algo.py <путь_к_картинке> [--thin] [--dpi 200]
Сохраняет рядом отладочные картинки: *_1thresh.png, *_2detect.png
"""
import sys, os
import cv2
import numpy as np

def fill_holes(bin_img):
    ff = bin_img.copy()
    h, w = bin_img.shape[:2]
    mask = np.zeros((h + 2, w + 2), np.uint8)
    cv2.floodFill(ff, mask, (0, 0), 255)
    inv = cv2.bitwise_not(ff)
    return bin_img | inv

def process(path, thin=False, dpi=200.0, min_area_cm=5.5, solid=0.0, max_disk=20, scale_factor=2):
    img = cv2.imread(path)
    if img is None:
        print("НЕ УДАЛОСЬ ОТКРЫТЬ:", path); return
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    if thin:
        block = max(15, (gray.shape[1] // 40) | 1)
        binary_full = cv2.adaptiveThreshold(
            gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, block, 10)
        print(f"[порог] адаптивный, block={block}")
    else:
        _, binary_full = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        print("[порог] Otsu")

    oh, ow = binary_full.shape
    sf = 1 if thin else max(1, scale_factor)
    binary = binary_full if sf == 1 else cv2.resize(
        binary_full, (ow // sf, oh // sf), interpolation=cv2.INTER_NEAREST)

    px_per_cm = (dpi / sf) / 2.54
    px_per_cm2 = px_per_cm * px_per_cm

    base = os.path.splitext(path)[0]
    cv2.imwrite(base + "_1thresh.png", binary_full)
    white = int((binary_full > 0).sum())
    print(f"[маска] {ow}x{oh}, белых пикс: {white} ({100*white/(ow*oh):.2f}%)")

    final = np.zeros_like(binary)
    disks = sorted(set(max(1, round(d / sf)) for d in [1] + list(range(3, max_disk + 1, 2))))
    areas, centroids = [], []

    for r in disks:
        se = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
        current = cv2.bitwise_and(binary, cv2.bitwise_not(final))
        temp = cv2.dilate(current, se)
        temp = fill_holes(temp)
        temp = cv2.erode(temp, se)
        cnts, _ = cv2.findContours(temp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        found_this = 0
        for c in cnts:
            a = cv2.contourArea(c)
            if a <= 0: continue
            a_cm = a / px_per_cm2
            hull = cv2.convexHull(c)
            ha = cv2.contourArea(hull)
            sol = a / ha if ha > 0 else 0
            if a_cm >= min_area_cm and sol > solid:
                frac = a / (binary.shape[0] * binary.shape[1])
                x, y, ww, hh = cv2.boundingRect(c)
                touches = x <= 1 or y <= 1 or x + ww >= binary.shape[1] - 1 or y + hh >= binary.shape[0] - 1
                if frac > 0.5: continue
                if touches and frac > 0.2: continue
                M = cv2.moments(c)
                if M["m00"] == 0: continue
                cx, cy = M["m10"] / M["m00"], M["m01"] / M["m00"]
                already = final[int(cy), int(cx)] > 0
                cv2.drawContours(final, [c], -1, 255, -1)
                if not already:
                    areas.append(a_cm); centroids.append((cx * sf, cy * sf)); found_this += 1
        if found_this:
            print(f"[диск r={r}] найдено новых фигур: {found_this}")

    # Рисуем результат на исходнике
    out = img.copy()
    mask_full = final if sf == 1 else cv2.resize(final, (ow, oh), interpolation=cv2.INTER_NEAREST)
    cnts, _ = cv2.findContours(mask_full, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    cv2.drawContours(out, cnts, -1, (0, 0, 255), max(2, 2 * sf))
    for (cx, cy), a_cm in zip(centroids, areas):
        cv2.putText(out, f"{a_cm:.1f}", (int(cx), int(cy)),
                    cv2.FONT_HERSHEY_SIMPLEX, max(1, ow / 900), (255, 0, 0), 2)
    cv2.imwrite(base + "_2detect.png", out)

    print(f"\nИТОГО фигур: {len(areas)}")
    for i, a in enumerate(areas):
        print(f"  #{i+1}: {a:.2f} см²")
    print(f"\nОтладка сохранена:\n  {base}_1thresh.png\n  {base}_2detect.png")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Использование: python3 test_algo.py <картинка> [--thin] [--dpi 200]"); sys.exit(1)
    path = sys.argv[1]
    thin = "--thin" in sys.argv
    dpi = 200.0
    if "--dpi" in sys.argv:
        dpi = float(sys.argv[sys.argv.index("--dpi") + 1])
    process(path, thin=thin, dpi=dpi)
