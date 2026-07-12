"""
combined_detector.py
====================
Pipeline:
  1. YOLO detect tất cả người trong ROI frame
  2. Crop từng người → detect mặt + seatbelt trên ảnh nhỏ đó
  3. Map tọa độ về frame gốc → vẽ

pip install ultralytics opencv-python mss numpy
"""

import cv2
import mss
import time
import queue
import threading
import numpy as np
from ultralytics import YOLO
from SeatbeltDetector import SeatbeltDetector
import settings

# ── Config ────────────────────────────────────────────────────────────────────
PERSON_MODEL   = "yolov8n.pt"
SEATBELT_MODEL = settings.YOLO_SEATBELT_MODEL
SEATBELT_CONF  = settings.SEATBELT_CONFIDENCE
PERSON_CONF    = 0.35
INFER_EVERY_N  = 3

WIN_SELECT = "Chon vung  |  Keo chuot  |  ENTER=OK  |  ESC=Huy"
WIN_DETECT = "Detect  |  R=Chon lai  |  Q=Thoat"

PERSON_COLORS = [
    (255, 150,   0),
    (  0, 200, 255),
    (150,   0, 255),
    (  0, 255, 150),
    (255,   0, 150),
    (255, 255,   0),
]

# ── ROI Selector ──────────────────────────────────────────────────────────────

def select_roi(screenshot: np.ndarray):
    state = {"drawing": False, "p1": (0,0), "p2": (0,0), "roi": None}

    def on_mouse(event, x, y, flags, _):
        if event == cv2.EVENT_LBUTTONDOWN:
            state.update({"drawing": True, "p1": (x,y), "p2": (x,y)})
        elif event == cv2.EVENT_MOUSEMOVE and state["drawing"]:
            state["p2"] = (x, y)
        elif event == cv2.EVENT_LBUTTONUP:
            state["drawing"] = False
            state["p2"] = (x, y)
            x1=min(state["p1"][0],x); y1=min(state["p1"][1],y)
            x2=max(state["p1"][0],x); y2=max(state["p1"][1],y)
            if x2-x1 > 10 and y2-y1 > 10:
                state["roi"] = (x1, y1, x2-x1, y2-y1)

    cv2.namedWindow(WIN_SELECT, cv2.WINDOW_NORMAL)
    cv2.setWindowProperty(WIN_SELECT, cv2.WND_PROP_FULLSCREEN, cv2.WINDOW_FULLSCREEN)
    cv2.setMouseCallback(WIN_SELECT, on_mouse)

    try:
        import ctypes
        cv2.imshow(WIN_SELECT, screenshot); cv2.waitKey(100)
        hwnd = ctypes.windll.user32.FindWindowW(None, WIN_SELECT)
        if hwnd:
            ctypes.windll.user32.SetForegroundWindow(hwnd)
    except Exception:
        pass

    while True:
        base = cv2.addWeighted(screenshot, 0.4, np.zeros_like(screenshot), 0.6, 0)
        if state["roi"]:
            rx,ry,rw,rh = state["roi"]
            base[ry:ry+rh, rx:rx+rw] = screenshot[ry:ry+rh, rx:rx+rw]
            cv2.rectangle(base, (rx,ry), (rx+rw,ry+rh), (0,220,255), 2)
            cv2.putText(base, f"{rw}x{rh}", (rx, max(ry-6,18)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0,220,255), 2)
        if state["drawing"]:
            x1=min(state["p1"][0],state["p2"][0]); y1=min(state["p1"][1],state["p2"][1])
            x2=max(state["p1"][0],state["p2"][0]); y2=max(state["p1"][1],state["p2"][1])
            if x2>x1 and y2>y1: base[y1:y2,x1:x2] = screenshot[y1:y2,x1:x2]
            cv2.rectangle(base, state["p1"], state["p2"], (0,220,255), 2)
        h,w = base.shape[:2]
        hint = "Keo chuot chon vung  |  ENTER = Bat dau detect  |  ESC = Huy"
        (tw,_),_ = cv2.getTextSize(hint, cv2.FONT_HERSHEY_SIMPLEX, 0.7, 2)
        cv2.rectangle(base, (0,h-50), (w,h), (10,10,10), -1)
        cv2.putText(base, hint, ((w-tw)//2, h-15),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0,220,255), 2)
        cv2.imshow(WIN_SELECT, base)
        key = cv2.waitKey(20) & 0xFF
        if key == 13 and state["roi"]: break
        elif key in (27, ord("q")): state["roi"] = None; break

    cv2.destroyWindow(WIN_SELECT); cv2.waitKey(1)
    return state["roi"]


# ── Detection Worker ──────────────────────────────────────────────────────────

class DetectionWorker(threading.Thread):
    """
    Nhận frame → detect tất cả người → với mỗi người:
      - Crop ảnh người ra
      - Detect face trên crop
      - Detect seatbelt trên crop (dùng SeatbeltDetector)
    Trả về list kết quả với tọa độ đã map về frame gốc.
    """

    def __init__(self, in_q: queue.Queue, out_q: queue.Queue):
        super().__init__(daemon=True)
        self.in_q  = in_q
        self.out_q = out_q
        self._stop = threading.Event()

        print("[Worker] Loading YOLO person model...")
        self.person_model = YOLO(PERSON_MODEL)
        print("[Worker] Loading SeatbeltDetector...")
        self.sb_det = SeatbeltDetector(model_path=SEATBELT_MODEL,
                                       confidence=SEATBELT_CONF)
        print("[Worker] Ready.")

    def stop(self): self._stop.set()

    def run(self):
        while not self._stop.is_set():
            try:
                frame = self.in_q.get(timeout=0.1)
            except queue.Empty:
                continue

            results = self._process(frame)

            # Luôn giữ kết quả mới nhất
            try: self.out_q.get_nowait()
            except: pass
            self.out_q.put(results)

    def _process(self, frame: np.ndarray) -> list:
        fh, fw = frame.shape[:2]

        # ── 1. Detect tất cả người trong frame ───────────────────────────────
        scale = min(1.0, 640 / max(fw, fh))   # không scale lên, chỉ scale xuống
        sw, sh = int(fw * scale), int(fh * scale)
        small  = cv2.resize(frame, (sw, sh)) if scale < 1.0 else frame

        p_res = self.person_model(small, classes=[0], conf=PERSON_CONF, verbose=False)

        person_bboxes = []
        for r in p_res:
            for box in r.boxes:
                # Map bbox về kích thước frame gốc
                bx1, by1, bx2, by2 = [int(v / scale) for v in box.xyxy[0]]
                # Clamp vào giới hạn frame
                bx1 = max(0, bx1); by1 = max(0, by1)
                bx2 = min(fw, bx2); by2 = min(fh, by2)
                if bx2 > bx1 and by2 > by1:
                    person_bboxes.append((bx1, by1, bx2, by2))

        # ── 2. Với mỗi người: crop → detect face + seatbelt ──────────────────
        results = []
        for (px1, py1, px2, py2) in person_bboxes:
            crop = frame[py1:py2, px1:px2]
            if crop.size == 0:
                continue
            ch, cw = crop.shape[:2]

            # --- Face: detect trên crop toàn thân, lấy class=0 (person/head) ---
            # Dùng 60% trên của crop người = phần đầu/mặt
            head_h = max(1, int(ch * 0.60))
            head_crop = crop[:head_h, :]
            face_res  = self.person_model(head_crop, classes=[0], conf=0.3, verbose=False)
            faces = []
            for r in face_res:
                for box in r.boxes:
                    fx1, fy1, fx2, fy2 = [int(v) for v in box.xyxy[0]]
                    # Map về frame gốc
                    faces.append((fx1+px1, fy1+py1, fx2+px1, fy2+py1))

            # --- Seatbelt: truyền frame gốc + bbox người vào SeatbeltDetector ---
            # SeatbeltDetector.detect() tự crop bên trong
            sb = self.sb_det.detect(frame, [px1, py1, px2, py2])

            results.append({
                "person_bbox": (px1, py1, px2, py2),
                "faces":       faces,
                "seatbelt":    sb,
            })

        return results


# ── Draw ──────────────────────────────────────────────────────────────────────

def draw_results(frame: np.ndarray, results: list):
    for i, res in enumerate(results):
        color = PERSON_COLORS[i % len(PERSON_COLORS)]
        px1, py1, px2, py2 = res["person_bbox"]

        # Box người
        cv2.rectangle(frame, (px1,py1), (px2,py2), color, 2)
        cv2.putText(frame, f"P{i+1}", (px1+4, py1+22),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)

        # Mặt
        for (fx1,fy1,fx2,fy2) in res["faces"]:
            cv2.rectangle(frame, (fx1,fy1), (fx2,fy2), (0,220,255), 2)
            cv2.putText(frame, "face", (fx1, max(fy1-5, 14)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0,220,255), 1)

        # Seatbelt
        sb = res["seatbelt"]
        if sb and sb.get("bbox"):
            sx1,sy1,sx2,sy2 = sb["bbox"]
            ok    = sb["has_seatbelt"]
            scol  = (0, 200, 0) if ok else (0, 0, 220)
            label = ("seatbelt" if ok else "NO seatbelt") + f" {sb['confidence']:.2f}"
            cv2.rectangle(frame, (sx1,sy1), (sx2,sy2), scol, 2)
            (tw,th),_ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 2)
            cv2.rectangle(frame, (sx1, sy1-th-6), (sx1+tw+4, sy1), scol, -1)
            cv2.putText(frame, label, (sx1+2, sy1-4),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255,255,255), 2)
        elif sb:
            cv2.putText(frame, "? seatbelt", (px1, min(py2+18, frame.shape[0]-5)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (120,120,255), 1)


def draw_hud(frame: np.ndarray, fps: float, n: int) -> np.ndarray:
    h, w = frame.shape[:2]
    bar = np.zeros((30, w, 3), np.uint8); bar[:] = (18,18,18)
    cv2.putText(bar, f"FPS:{fps:.1f}", (8, 20),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0,255,255), 2)
    cv2.putText(bar, f"Nguoi: {n}", (120, 20),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (200,200,200), 1)
    cv2.putText(bar, "R=Chon lai  Q=Thoat", (w-200, 20),
                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (140,140,140), 1)
    return np.vstack([frame, bar])


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    in_q  = queue.Queue(maxsize=1)
    out_q = queue.Queue(maxsize=1)
    worker = DetectionWorker(in_q, out_q)
    worker.start()

    with mss.mss() as sct:
        monitor = sct.monitors[1]

        while True:
            # Chọn ROI
            shot = cv2.cvtColor(np.array(sct.grab(monitor)), cv2.COLOR_BGRA2BGR)
            roi  = select_roi(shot)
            if roi is None:
                break

            rx, ry, rw, rh = roi
            region = {"left": monitor["left"]+rx, "top": monitor["top"]+ry,
                      "width": rw, "height": rh}
            print(f"Detect trên {rw}x{rh} @ ({rx},{ry})")

            cv2.namedWindow(WIN_DETECT, cv2.WINDOW_NORMAL)
            cv2.resizeWindow(WIN_DETECT, rw, rh+30)

            frame_n = 0
            t0 = time.time(); fps_cnt = 0; fps = 0.0
            latest = []
            reselect = False

            while True:
                raw   = sct.grab(region)
                frame = cv2.cvtColor(np.array(raw), cv2.COLOR_BGRA2BGR)
                frame_n += 1; fps_cnt += 1

                # Gửi vào worker mỗi N frame (non-blocking)
                if frame_n % INFER_EVERY_N == 0:
                    try: in_q.get_nowait()
                    except: pass
                    in_q.put(frame.copy())

                # Lấy kết quả mới nhất (non-blocking)
                try: latest = out_q.get_nowait()
                except: pass

                # Vẽ
                display = frame.copy()
                draw_results(display, latest)

                elapsed = time.time() - t0
                if elapsed >= 1.0:
                    fps = fps_cnt / elapsed
                    t0 = time.time(); fps_cnt = 0

                display = draw_hud(display, fps, len(latest))
                cv2.imshow(WIN_DETECT, display)

                key = cv2.waitKey(1) & 0xFF
                if key == ord("q"):
                    break
                elif key == ord("r"):
                    reselect = True; break

            cv2.destroyWindow(WIN_DETECT)
            if not reselect:
                break

    worker.stop()
    worker.join(timeout=2)
    cv2.destroyAllWindows()
    print("Thoát.")


if __name__ == "__main__":
    main()
