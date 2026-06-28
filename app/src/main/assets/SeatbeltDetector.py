"""
Seatbelt Detector - Detect dây an toàn bằng YOLOv8
"""

import os
import numpy as np
from ultralytics import YOLO

# Tên 2 class trong model đã train
CLASS_SEATBELT = "seatbelt"
CLASS_NO_SEATBELT = "no-seatbelt"


class SeatbeltDetector:
    def __init__(self, model_path: str = "best.pt", confidence: float = 0.25):
        """
        Load model YOLO dây an toàn.

        Args:
            model_path: Đường dẫn tới file .pt đã train
            confidence: Ngưỡng confidence tối thiểu để chấp nhận kết quả
        """
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"Không tìm thấy model: {model_path}")

        self.model = YOLO(model_path)
        self.confidence = confidence
        print(f"[SeatbeltDetector] Đã load model: {model_path}")

    def detect(self, frame: np.ndarray, person_bbox: list) -> dict:
        """
        Detect dây an toàn trong vùng bbox của người.

        Ý tưởng: crop ảnh về vùng chứa người trước, rồi mới chạy YOLO
        → giúp model tập trung đúng chỗ, bỏ qua nhiễu nền.

        Args:
            frame:       numpy array ảnh gốc (BGR, từ OpenCV)
            person_bbox: [x1, y1, x2, y2] - vùng chứa người

        Returns:
            dict gồm:
                - has_seatbelt (bool): True nếu phát hiện đeo dây
                - class_name  (str):  "seatbelt" hoặc "no-seatbelt"
                - confidence  (float): độ tin cậy của kết quả
                - bbox        (list | None): [x1,y1,x2,y2] trong toạ độ ảnh GỐC
        """
        x1, y1, x2, y2 = [int(v) for v in person_bbox]

        # --- Crop vùng người ra khỏi frame gốc ---
        h, w = frame.shape[:2]
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(w, x2), min(h, y2)
        crop = frame[y1:y2, x1:x2]

        if crop.size == 0:
            return {"has_seatbelt": False, "class_name": None, "confidence": 0.0, "bbox": None}

        # --- Chạy YOLO trên vùng crop ---
        results = self.model(crop, conf=self.confidence, verbose=False)

        best = self._pick_best(results)
        if best is None:
            # YOLO không detect được gì trong vùng này
            return {"has_seatbelt": False, "class_name": None, "confidence": 0.0, "bbox": None}

        cls_name, conf, (bx1, by1, bx2, by2) = best

        # Chuyển toạ độ bbox từ crop → ảnh gốc
        abs_bbox = [bx1 + x1, by1 + y1, bx2 + x1, by2 + y1]

        return {
            "has_seatbelt": cls_name == CLASS_SEATBELT,
            "class_name":   cls_name,
            "confidence":   round(conf, 3),
            "bbox":         abs_bbox,
        }

    def detect_raw(self, frame: np.ndarray) -> list[dict]:
        """
        Detect toàn bộ ảnh không cần bbox người.
        Tiện dùng khi test nhanh một ảnh đơn.

        Returns:
            Danh sách các kết quả detect được (mỗi phần tử là dict giống detect())
        """
        results = self.model(frame, conf=self.confidence, verbose=False)
        output = []
        for r in results:
            for box in r.boxes:
                cls_id  = int(box.cls[0])
                cls_name = self.model.names[cls_id]
                conf     = float(box.conf[0])
                x1b, y1b, x2b, y2b = [int(v) for v in box.xyxy[0]]
                output.append({
                    "has_seatbelt": cls_name == CLASS_SEATBELT,
                    "class_name":   cls_name,
                    "confidence":   round(conf, 3),
                    "bbox":         [x1b, y1b, x2b, y2b],
                })
        return output

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _pick_best(self, results) -> tuple | None:
        """
        Từ kết quả YOLO, chọn detection có confidence cao nhất.

        Returns:
            (class_name, confidence, (x1,y1,x2,y2)) hoặc None nếu rỗng
        """
        best_conf = -1
        best = None
        for r in results:
            for box in r.boxes:
                conf = float(box.conf[0])
                if conf > best_conf:
                    best_conf = conf
                    cls_id    = int(box.cls[0])
                    cls_name  = self.model.names[cls_id]
                    coords    = tuple(int(v) for v in box.xyxy[0])
                    best = (cls_name, conf, coords)
        return best
