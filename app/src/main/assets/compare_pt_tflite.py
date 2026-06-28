"""
So sánh kết quả best.pt vs best.tflite trên cùng 1 ảnh.
Chạy: python compare_pt_tflite.py [đường dẫn ảnh (tuỳ chọn)]
"""

import sys
import os
import numpy as np
import cv2

# Đảm bảo import được settings từ cùng thư mục
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ultralytics import YOLO

# ─────────────────────────────────────────────
# Đường dẫn (tuyệt đối, không phụ thuộc CWD)
# ─────────────────────────────────────────────
ASSETS_DIR  = os.path.dirname(os.path.abspath(__file__))
PT_PATH     = os.path.join(ASSETS_DIR, "best.pt")
TFLITE_PATH = os.path.join(ASSETS_DIR, "best.tflite")

# ─────────────────────────────────────────────
# Kiểm tra file tồn tại
# ─────────────────────────────────────────────
for p in [PT_PATH, TFLITE_PATH]:
    if not os.path.exists(p):
        print(f"[LỖI] Không tìm thấy file: {p}")
        sys.exit(1)

print(f"[OK] best.pt    : {PT_PATH}  ({os.path.getsize(PT_PATH)/1e6:.1f} MB)")
print(f"[OK] best.tflite: {TFLITE_PATH}  ({os.path.getsize(TFLITE_PATH)/1e6:.1f} MB)")

# ─────────────────────────────────────────────
# Load models
# ─────────────────────────────────────────────
print("\n[INFO] Đang load best.pt ...")
model_pt = YOLO(PT_PATH)

print("[INFO] Đang load best.tflite ...")
model_tflite = YOLO(TFLITE_PATH)

print("\n[INFO] Tên class trong model:")
print("  PT     :", model_pt.names)
print("  TFLite :", model_tflite.names)

# ─────────────────────────────────────────────
# Chọn ảnh test
# ─────────────────────────────────────────────
if len(sys.argv) > 1:
    img_path = sys.argv[1]
    if not os.path.exists(img_path):
        print(f"[LỖI] Không tìm thấy ảnh: {img_path}")
        sys.exit(1)
    frame = cv2.imread(img_path)
    print(f"\n[INFO] Ảnh test: {img_path}  ({frame.shape[1]}x{frame.shape[0]})")
else:
    print("\n[WARN] Không có ảnh truyền vào → dùng ảnh noise 640x640 để test pipeline.")
    img_path = None
    frame = np.random.randint(0, 255, (640, 640, 3), dtype=np.uint8)

CONF_THRESH = 0.1  # Ngưỡng thấp để thấy TẤT CẢ detection kể cả yếu

# ─────────────────────────────────────────────
# Chạy inference
# ─────────────────────────────────────────────
print(f"\n{'='*60}")
print(f"  INFERENCE (conf >= {CONF_THRESH})")
print(f"{'='*60}")

def run_and_print(model, label: str, frame: np.ndarray) -> list:
    results = model(frame, conf=CONF_THRESH, verbose=False)
    detections = []
    for r in results:
        for box in r.boxes:
            cls_id   = int(box.cls[0])
            cls_name = model.names[cls_id]
            conf     = float(box.conf[0])
            x1, y1, x2, y2 = [int(v) for v in box.xyxy[0]]
            detections.append({
                "class": cls_name,
                "conf":  conf,
                "bbox":  [x1, y1, x2, y2],
            })

    print(f"\n[{label}] -> {len(detections)} detection(s):")
    if not detections:
        print("   (Khong detect duoc gi)")
    for d in sorted(detections, key=lambda x: -x["conf"]):
        status = "SEATBELT" if d["class"] == "seatbelt" else "NO-BELT"
        print(f"   [{status}]  class={d['class']:<14} conf={d['conf']:.3f}  bbox={d['bbox']}")
    return detections

dets_pt     = run_and_print(model_pt,     "best.pt    ", frame)
dets_tflite = run_and_print(model_tflite, "best.tflite", frame)

# ─────────────────────────────────────────────
# So sánh top-1
# ─────────────────────────────────────────────
print(f"\n{'='*60}")
print("  SO SANH TOP-1")
print(f"{'='*60}")

def top1(dets):
    return max(dets, key=lambda x: x["conf"]) if dets else None

t_pt     = top1(dets_pt)
t_tflite = top1(dets_tflite)

if t_pt is None and t_tflite is None:
    print(f"[WARN] Ca hai model deu khong detect voi conf >= {CONF_THRESH}")
elif t_pt is None:
    print(f"[WARN] PT khong detect, TFLite detect: {t_tflite}")
elif t_tflite is None:
    print(f"[WARN] PT detect {t_pt}, TFLite khong detect gi!")
else:
    match     = t_pt["class"] == t_tflite["class"]
    diff_conf = abs(t_pt["conf"] - t_tflite["conf"])
    print(f"PT     top-1: class={t_pt['class']:<14} conf={t_pt['conf']:.3f}")
    print(f"TFLite top-1: class={t_tflite['class']:<14} conf={t_tflite['conf']:.3f}")
    print(f"Class khop  : {'CO' if match else 'KHONG'}")
    print(f"Chenh conf  : {diff_conf:.3f}  {'OK (< 0.05)' if diff_conf < 0.05 else 'Chenh nhieu!'}")

# ─────────────────────────────────────────────
# Lưu ảnh so sánh
# ─────────────────────────────────────────────
if img_path:
    out_path = os.path.join(ASSETS_DIR, "compare_result.jpg")
    h, w = frame.shape[:2]
    gap = 10
    canvas = np.zeros((h, w * 2 + gap, 3), dtype=np.uint8)

    left  = frame.copy()
    right = frame.copy()

    def draw_dets(img, dets, title):
        for d in dets:
            x1, y1, x2, y2 = d["bbox"]
            color = (0, 200, 0) if d["class"] == "seatbelt" else (0, 0, 220)
            label = f"{d['class']} {d['conf']:.2f}"
            cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
            (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 2)
            cv2.rectangle(img, (x1, y1 - 22), (x1 + tw + 4, y1), color, -1)
            cv2.putText(img, label, (x1 + 2, y1 - 5), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2)
        cv2.putText(img, title, (10, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 220, 255), 2)

    draw_dets(left,  dets_pt,     "best.pt")
    draw_dets(right, dets_tflite, "best.tflite")

    canvas[:, :w]           = left
    canvas[:, w + gap:]     = right

    cv2.imwrite(out_path, canvas)
    print(f"\n[INFO] Anh so sanh da luu: {out_path}")

print("\n[DONE]")
