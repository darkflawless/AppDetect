"""
=============================================================
  convert_int8_tflite.py
  Convert best.pt (YOLOv8/YOLO) -> TFLite INT8
  Cach chay:
      python convert_int8_tflite.py
  Yeu cau:
      pip install ultralytics tensorflow numpy pillow
=============================================================
"""

import os
import sys
import shutil
import numpy as np

# === CAU HINH ===============================================================
# Duong dan file .pt goc
PT_MODEL_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "best.pt")

# Thu muc output (cung thu muc voi .pt)
OUTPUT_DIR = os.path.dirname(os.path.abspath(__file__))

# Kich thuoc anh input (phai khop voi luc train model)
IMG_SIZE = 640          # Thay sang 320 neu model train voi 320

# So anh calibration cho INT8 (cang nhieu cang chinh xac, toi thieu 100)
CALIBRATION_IMAGES = 100

# Neu ban co thu muc anh thuc te, set vao day de calibration chinh xac hon
# De None thi script se dung random noise (nhanh nhung kem chinh xac hon)
CALIBRATION_DATA_DIR = None  # Vi du: r"C:\dataset\images\val"
# ============================================================================


def check_dependencies():
    """Kiem tra cac thu vien can thiet."""
    missing = []
    try:
        import ultralytics
        print(f"  [OK] ultralytics: {ultralytics.__version__}")
    except ImportError:
        missing.append("ultralytics")

    try:
        import tensorflow as tf
        print(f"  [OK] tensorflow:  {tf.__version__}")
    except ImportError:
        missing.append("tensorflow")

    try:
        import PIL
        print(f"  [OK] pillow:       OK")
    except ImportError:
        missing.append("pillow")

    if missing:
        print(f"\n[FAIL] Thieu thu vien: {', '.join(missing)}")
        print(f"   Cai dat bang lenh:")
        print(f"   pip install {' '.join(missing)}")
        sys.exit(1)


def build_calibration_dataset(img_size, n_images, data_dir):
    """
    Tao generator anh cho calibration INT8.
    - Neu data_dir duoc cung cap: doc anh thuc te tu thu muc do
    - Neu khong: dung random noise
    """
    from PIL import Image

    real_images = []
    if data_dir and os.path.isdir(data_dir):
        exts = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
        real_images = [
            os.path.join(data_dir, f)
            for f in os.listdir(data_dir)
            if f.lower().endswith(exts)
        ]
        if not real_images:
            print(f"  [WARN] Khong tim thay anh trong {data_dir}, dung random noise.")
            real_images = []
        else:
            import random
            random.shuffle(real_images)
            real_images = real_images[:n_images]
            print(f"  [INFO] Dung {len(real_images)} anh thuc te tu: {data_dir}")

    if not real_images:
        print(f"  [INFO] Dung {n_images} anh random noise de calibration")
        print(f"         (De chinh xac hon, hay set CALIBRATION_DATA_DIR)")

    def representative_dataset_gen():
        if real_images:
            for img_path in real_images:
                try:
                    img = Image.open(img_path).convert("RGB")
                    img = img.resize((img_size, img_size), Image.BILINEAR)
                    arr = np.array(img, dtype=np.float32) / 255.0
                    arr = arr[np.newaxis, ...]  # (1, H, W, 3)
                    yield [arr]
                except Exception as e:
                    print(f"  [WARN] Bo qua anh loi: {img_path} ({e})")
        else:
            rng = np.random.default_rng(42)
            for _ in range(n_images):
                arr = rng.random((1, img_size, img_size, 3)).astype(np.float32)
                yield [arr]

    return representative_dataset_gen


def convert_with_ultralytics():
    """
    Phuong phap 1: Dung ultralytics export truc tiep.
    ultralytics tu xu ly: PT -> ONNX -> TFLite INT8
    """
    from ultralytics import YOLO

    print("\n[STEP] Load model:", PT_MODEL_PATH)
    model = YOLO(PT_MODEL_PATH)
    print(f"  Model task: {model.task}")
    print(f"  Input size: {IMG_SIZE}x{IMG_SIZE}")

    print("\n[STEP] Bat dau export TFLite INT8 (co the mat vai phut)...")

    export_path = model.export(
        format="tflite",
        imgsz=IMG_SIZE,
        int8=True,
        data=None,
        nms=False,
        simplify=True,
    )

    return str(export_path) if export_path else None


def convert_with_tf_converter(saved_model_path):
    """
    Phuong phap 2 (nang cao): Dung TF Lite Converter truc tiep.
    Dung khi muon kiem soat calibration dataset.
    """
    import tensorflow as tf

    print(f"\n[STEP] Convert SavedModel -> TFLite INT8...")
    print(f"   Input: {saved_model_path}")

    converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_path)

    # Cau hinh INT8 Full Integer Quantization
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS_INT8,
        tf.lite.OpsSet.TFLITE_BUILTINS,
    ]
    converter.inference_input_type  = tf.uint8
    converter.inference_output_type = tf.uint8

    calibration_gen = build_calibration_dataset(
        img_size=IMG_SIZE,
        n_images=CALIBRATION_IMAGES,
        data_dir=CALIBRATION_DATA_DIR,
    )
    converter.representative_dataset = calibration_gen

    print("   [WAIT] Dang quantize (co the mat 2-5 phut)...")
    tflite_model = converter.convert()

    out_path = os.path.join(OUTPUT_DIR, "best_int8.tflite")
    with open(out_path, "wb") as f:
        f.write(tflite_model)

    return out_path


def verify_tflite(tflite_path):
    """Kiem tra file TFLite da tao ra."""
    import tensorflow as tf

    print(f"\n[VERIFY] Kiem tra file: {tflite_path}")
    try:
        interpreter = tf.lite.Interpreter(model_path=tflite_path)
        interpreter.allocate_tensors()

        in_details  = interpreter.get_input_details()
        out_details = interpreter.get_output_details()

        print("\n  Input tensors:")
        for d in in_details:
            print(f"    name  : {d['name']}")
            print(f"    shape : {d['shape']}")
            print(f"    dtype : {d['dtype']}")

        print("\n  Output tensors:")
        for d in out_details:
            print(f"    name  : {d['name']}")
            print(f"    shape : {d['shape']}")
            print(f"    dtype : {d['dtype']}")

        size_mb = os.path.getsize(tflite_path) / (1024 * 1024)
        print(f"\n  File size: {size_mb:.2f} MB")
        print(f"  [OK] TFLite file hop le!")
    except Exception as e:
        print(f"  [FAIL] Loi khi kiem tra: {e}")


def find_output_tflite():
    """Tim file tflite moi nhat trong thu muc output."""
    candidates = []
    for root, dirs, files in os.walk(OUTPUT_DIR):
        for f in files:
            if f.endswith(".tflite"):
                candidates.append(os.path.join(root, f))
    if candidates:
        return max(candidates, key=os.path.getmtime)
    return None


def main():
    print("=" * 60)
    print("  YOLO PT -> TFLite INT8 Converter")
    print("=" * 60)

    # 1. Kiem tra dependencies
    print("\n[1/4] Kiem tra thu vien...")
    check_dependencies()

    # 2. Kiem tra file input
    if not os.path.exists(PT_MODEL_PATH):
        print(f"\n[FAIL] Khong tim thay file: {PT_MODEL_PATH}")
        sys.exit(1)

    size_mb = os.path.getsize(PT_MODEL_PATH) / (1024 * 1024)
    print(f"\n[2/4] File input: {PT_MODEL_PATH}")
    print(f"      Kich thuoc : {size_mb:.1f} MB")

    # 3. Convert
    print(f"\n[3/4] Bat dau convert...")
    print("-" * 60)

    final_path = None

    try:
        export_path = convert_with_ultralytics()

        # Tim file tflite duoc tao ra
        if export_path and os.path.exists(export_path):
            final_path = export_path
        else:
            final_path = find_output_tflite()

        if final_path:
            print(f"\n[OK] Export thanh cong: {final_path}")

            # Copy ve thu muc assets voi ten chuan
            dest = os.path.join(OUTPUT_DIR, "best_int8.tflite")
            if os.path.abspath(final_path) != os.path.abspath(dest):
                shutil.copy2(final_path, dest)
                print(f"[COPY] Da copy sang: {dest}")
                final_path = dest
        else:
            raise RuntimeError("Khong tim thay file output sau export")

    except Exception as e:
        print(f"\n[WARN] ultralytics export gap loi: {e}")
        print("\n[INFO] Thu phuong phap thu cong voi TF SavedModel...")

        saved_model = input(
            "\nNhap duong dan SavedModel (thu muc _saved_model) neu da co,\n"
            "hoac Enter de bo qua: "
        ).strip()

        if saved_model and os.path.isdir(saved_model):
            final_path = convert_with_tf_converter(saved_model)
            print(f"\n[OK] TFLite INT8 da duoc tao: {final_path}")
        else:
            print("\n[FAIL] Khong convert duoc. Hay kiem tra:")
            print("   1. pip install ultralytics tensorflow onnx onnx2tf")
            print("   2. Dam bao best.pt la YOLO model hop le")
            print("   3. Xem log loi phia tren de debug")
            sys.exit(1)

    # 4. Verify
    print(f"\n[4/4] Kiem tra ket qua...")
    if final_path and os.path.exists(final_path):
        verify_tflite(final_path)

    print("\n" + "=" * 60)
    print("  HOAN THANH!")
    print("=" * 60)
    print(f"\n  Output: {final_path}")
    print("\n  Luu y khi dung tren Android:")
    print("  - Them best_int8.tflite vao thu muc assets/ cua Android project")
    print("  - Su dung TFLite Interpreter hoac TFLite Task Library")
    print("  - Input: UINT8 [1, IMG_SIZE, IMG_SIZE, 3]")
    print("  - Post-process output de lay boxes, scores, classes")


if __name__ == "__main__":
    main()
