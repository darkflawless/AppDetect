import cv2
import sys
import os
import time
import mss
import numpy as np

# Thêm thư mục gốc vào sys.path để import được src
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from ultralytics import YOLO
from SeatbeltDetector import SeatbeltDetector
import settings

WINDOW_NAME = "AI Camera Feed (Screen Capture)"
CONTROL_HEIGHT = 86
BUTTON_W = 142
BUTTON_H = 34


def grab_screen(sct: mss.mss, monitor: dict) -> np.ndarray:
    shot = sct.grab(monitor)
    frame = np.array(shot)
    return cv2.cvtColor(frame, cv2.COLOR_BGRA2BGR)


def ask_roi(sct: mss.mss, monitor: dict) -> tuple[int, int, int, int] | None:
    preview = grab_screen(sct, monitor)
    roi = cv2.selectROI(
        "Chon vung detect (Quet chuot roi an ENTER)",
        preview,
        showCrosshair=True,
        fromCenter=False,
    )
    cv2.destroyWindow("Chon vung detect (Quet chuot roi an ENTER)")
    if roi == (0, 0, 0, 0):
        return None
    x, y, w, h = roi
    return int(x), int(y), int(w), int(h)


def draw_button(frame: np.ndarray, rect: tuple[int, int, int, int], text: str, active: bool = False) -> None:
    x, y, w, h = rect
    fill = (0, 180, 0) if active else (60, 60, 60)
    cv2.rectangle(frame, (x, y), (x + w, y + h), fill, -1)
    cv2.rectangle(frame, (x, y), (x + w, y + h), (255, 255, 255), 1)
    (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 2)
    tx = x + (w - tw) // 2
    ty = y + (h + th) // 2 - 3
    cv2.putText(frame, text, (tx, ty), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2)


def point_in_rect(x: int, y: int, rect: tuple[int, int, int, int]) -> bool:
    rx, ry, rw, rh = rect
    return rx <= x <= rx + rw and ry <= y <= ry + rh


def draw_minimap(full_frame: np.ndarray, roi: tuple[int, int, int, int], monitor: dict) -> np.ndarray:
    screen_w = monitor["width"]
    screen_h = monitor["height"]
    
    # Kích thước bản đồ nhỏ (tỷ lệ 16:9)
    map_w = 160
    map_h = int(map_w * screen_h / screen_w)
    
    # Thu nhỏ ảnh màn hình
    minimap = cv2.resize(full_frame, (map_w, map_h))
    
    # Scale tọa độ ROI hiện tại lên bản đồ nhỏ
    rx, ry, rw, rh = roi
    mx1 = int(rx * map_w / screen_w)
    my1 = int(ry * map_h / screen_h)
    mx2 = int((rx + rw) * map_w / screen_w)
    my2 = int((ry + rh) * map_h / screen_h)
    
    # Vẽ ô vuông màu cam biểu thị vùng capture
    cv2.rectangle(minimap, (mx1, my1), (mx2, my2), (0, 165, 255), 2)
    # Vẽ viền cho bản đồ nhỏ
    cv2.rectangle(minimap, (0, 0), (map_w - 1, map_h - 1), (255, 255, 255), 1)
    
    return minimap


class ScreenDetectUI:
    def __init__(self):
        self.running = True  # Tự động chạy nhận diện khi bật lên
        self.request_reselect = False
        self.request_quit = False
        self.roi: tuple[int, int, int, int] | None = None
        self.start_rect = (10, 10, BUTTON_W, BUTTON_H)
        self.stop_rect = (162, 10, BUTTON_W, BUTTON_H)
        self.reset_rect = (314, 10, BUTTON_W, BUTTON_H)
        self.quit_rect = (466, 10, BUTTON_W, BUTTON_H)

    def on_mouse(self, event, x, y, flags, param):
        if event != cv2.EVENT_LBUTTONDOWN:
            return

        if point_in_rect(x, y, self.start_rect):
            self.running = True
        elif point_in_rect(x, y, self.stop_rect):
            self.running = False
        elif point_in_rect(x, y, self.reset_rect):
            self.request_reselect = True
        elif point_in_rect(x, y, self.quit_rect):
            self.request_quit = True

    def build_control_panel(self, width: int) -> np.ndarray:
        panel = np.zeros((CONTROL_HEIGHT, width, 3), dtype=np.uint8)
        panel[:] = (20, 20, 20)  # Nền tối cho thanh điều khiển

        draw_button(panel, self.start_rect, "START", active=self.running)
        draw_button(panel, self.stop_rect, "STOP", active=not self.running)
        draw_button(panel, self.reset_rect, "RESET ROI")
        draw_button(panel, self.quit_rect, "QUIT")

        cv2.putText(
            panel,
            "i/k/j/l or Arrow Keys = Move ROI | [/] = Resize | R = Reset | Q = Quit",
            (10, CONTROL_HEIGHT - 10),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.4,
            (180, 180, 180),
            1,
        )
        cv2.putText(
            panel,
            f"State: {'RUNNING' if self.running else 'PAUSED'}",
            (width - 180, 28),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.6,
            (0, 220, 0) if self.running else (0, 170, 255),
            2,
        )
        return panel


def main():
    print("[INFO] Khởi tạo models...")
    try:
        person_model = YOLO("yolov8n.pt")
        seatbelt_det = SeatbeltDetector(
            model_path=settings.YOLO_SEATBELT_MODEL,
            confidence=settings.SEATBELT_CONFIDENCE,
        )
    except Exception as e:
        print(f"[LỖI] Không thể khởi tạo model: {e}")
        return

    print("[INFO] Đang mở màn hình máy tính...")
    with mss.mss() as sct:
        monitor = sct.monitors[1]  # Màn hình chính
        screen_w = monitor["width"]
        screen_h = monitor["height"]

        # Thiết lập ROI mặc định: 640x640 nằm giữa màn hình
        roi_w = 640
        roi_h = 640
        roi_x = max(0, (screen_w - roi_w) // 2)
        roi_y = max(0, (screen_h - roi_h) // 2)
        roi = (roi_x, roi_y, roi_w, roi_h)

        ui = ScreenDetectUI()
        ui.roi = roi

        cv2.namedWindow(WINDOW_NAME, cv2.WINDOW_NORMAL)
        cv2.setMouseCallback(WINDOW_NAME, ui.on_mouse)

        print("\n[HƯỚNG DẪN DỊCH CHUYỂN KHUNG CAM]")
        print("- Di chuyển khung chụp: Dùng các phím i (lên), k (xuống), j (trái), l (phải) hoặc phím Mũi Tên.")
        print("- Thay đổi kích thước: Dùng phím [ (thu nhỏ) và ] (phóng to).")
        print("- Chọn lại vùng tùy ý: Bấm RESET ROI trên màn hình hoặc ấn phím R.")
        print("- Phím tắt khác: S=Start, T=Stop, Q=Thoát.\n")

        infer_every_n = 3
        infer_size = 640
        frame_count = 0
        last_persons = []
        last_seatbelts = []
        fps_timer = time.time()
        fps_frames = 0
        fps = 0.0

        # Step di chuyển và thay đổi kích thước
        move_step = 25
        resize_step = 50

        while True:
            if ui.request_quit:
                print("[INFO] Đã nhận lệnh thoát.")
                break

            if ui.request_reselect:
                new_roi = ask_roi(sct, monitor)
                ui.request_reselect = False
                if new_roi is None:
                    print("[INFO] Giữ nguyên ROI hiện tại.")
                else:
                    roi_x, roi_y, roi_w, roi_h = new_roi
                    roi = new_roi
                    ui.roi = new_roi
                    ui.running = True  # Tự động tiếp tục detect vùng mới
                    print(f"[INFO] ROI mới được chọn: x={roi_x}, y={roi_y}, w={roi_w}, h={roi_h}")

            full_frame = grab_screen(sct, monitor)
            if full_frame.size == 0:
                print("[LỖI] Không chụp được màn hình.")
                break

            # Cắt lấy vùng ROI hiện tại
            roi_frame = full_frame[roi_y:roi_y + roi_h, roi_x:roi_x + roi_w].copy()
            if roi_frame.size == 0:
                print("[LỖI] Vùng ROI không hợp lệ.")
                break

            frame_count += 1
            fps_frames += 1

            # Chạy AI phát hiện người & dây an toàn nếu đang running
            if ui.running and frame_count % infer_every_n == 0:
                h, w = roi_frame.shape[:2]
                scale = infer_size / w
                small = cv2.resize(roi_frame, (infer_size, max(1, int(h * scale))))

                p_results = person_model(small, classes=[0], conf=0.4, verbose=False)
                last_persons = []
                for r in p_results:
                    for box in r.boxes:
                        px1, py1, px2, py2 = [int(v / scale) for v in box.xyxy[0]]
                        last_persons.append([px1, py1, px2, py2])

                last_seatbelts = []
                for pbbox in last_persons:
                    result = seatbelt_det.detect(roi_frame, pbbox)
                    last_seatbelts.append((pbbox, result))

            # Vẽ kết quả detect lên roi_frame (tọa độ tương đối)
            for pbbox, sb_result in last_seatbelts:
                px1, py1, px2, py2 = pbbox
                cv2.rectangle(roi_frame, (px1, py1), (px2, py2), (255, 150, 0), 2)  # Box người

                if sb_result["bbox"] is not None:
                    sx1, sy1, sx2, sy2 = sb_result["bbox"]
                    color = (0, 200, 0) if sb_result["has_seatbelt"] else (0, 0, 220)
                    label = f"{sb_result['class_name']}: {sb_result['confidence']:.2f}"

                    cv2.rectangle(roi_frame, (sx1, sy1), (sx2, sy2), color, 2)  # Box seatbelt
                    (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 2)
                    cv2.rectangle(roi_frame, (sx1, sy1 - 22), (sx1 + tw + 4, sy1), color, -1)
                    cv2.putText(roi_frame, label, (sx1 + 2, sy1 - 5),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2)
                else:
                    cv2.putText(roi_frame, "? no seatbelt det", (px1, py1 - 8),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (128, 128, 128), 1)

            if not last_persons and ui.running:
                cv2.putText(roi_frame, "Khong thay nguoi", (10, 30),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.65, (100, 100, 255), 2)

            # Vẽ Mini-Map lên roi_frame ở góc dưới bên phải
            minimap = draw_minimap(full_frame, roi, monitor)
            h_roi, w_roi = roi_frame.shape[:2]
            map_h, map_w = minimap.shape[:2]
            if h_roi > map_h + 30 and w_roi > map_w + 30:
                my_start = h_roi - map_h - 10
                mx_start = w_roi - map_w - 10
                roi_frame[my_start:my_start + map_h, mx_start:mx_start + map_w] = minimap
                cv2.putText(roi_frame, "SCREEN MAP", (mx_start, my_start - 5),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.35, (0, 165, 255), 1)

            # Ghép Control Panel và roi_frame thành 1 ảnh preview để hiển thị
            max_w = max(640, roi_w)
            preview = np.zeros((CONTROL_HEIGHT + roi_h, max_w, 3), dtype=np.uint8)

            panel = ui.build_control_panel(max_w)
            preview[0:CONTROL_HEIGHT, 0:max_w] = panel
            preview[CONTROL_HEIGHT:CONTROL_HEIGHT + roi_h, 0:roi_w] = roi_frame

            # Tính FPS và vẽ lên góc dưới bên trái của window preview
            elapsed = time.time() - fps_timer
            if elapsed >= 1.0:
                fps = fps_frames / elapsed
                fps_timer = time.time()
                fps_frames = 0

            cv2.putText(preview, f"FPS: {fps:.1f}", (10, preview.shape[0] - 12),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.75, (0, 255, 255), 2)

            # Hiển thị
            cv2.imshow(WINDOW_NAME, preview)

            # Đọc phím điều khiển (sử dụng waitKeyEx để hỗ trợ phím mũi tên)
            key_ex = cv2.waitKeyEx(1)
            key = key_ex & 0xFF if key_ex != -1 else -1

            moved = False
            resized = False

            # Xử lý dịch chuyển và đổi kích thước bằng phím
            if key == ord("q"):
                ui.request_quit = True
            elif key == ord("s"):
                ui.running = True
            elif key == ord("t"):
                ui.running = False
            elif key == ord("r"):
                ui.request_reselect = True
            # Đi sang trái (j hoặc Mũi Tên Trái)
            elif key == ord("j") or key_ex == 2424832 or key == 81:
                roi_x = max(0, roi_x - move_step)
                moved = True
            # Đi sang phải (l hoặc Mũi Tên Phải)
            elif key == ord("l") or key_ex == 2555904 or key == 83:
                roi_x = min(screen_w - roi_w, roi_x + move_step)
                moved = True
            # Đi lên (i hoặc Mũi Tên Lên)
            elif key == ord("i") or key_ex == 2490368 or key == 82:
                roi_y = max(0, roi_y - move_step)
                moved = True
            # Đi xuống (k hoặc Mũi Tên Xuống)
            elif key == ord("k") or key_ex == 2621440 or key == 84:
                roi_y = min(screen_h - roi_h, roi_y + move_step)
                moved = True
            # Tăng kích thước (] hoặc +)
            elif key == ord("]") or key == ord("+") or key == ord("="):
                new_w = min(screen_w - roi_x, roi_w + resize_step)
                new_h = min(screen_h - roi_y, roi_h + resize_step)
                new_size = min(new_w, new_h)
                max_size = min(screen_w - roi_x, screen_h - roi_y)
                new_size = min(new_size, max_size)
                roi_w = roi_h = new_size
                resized = True
            # Giảm kích thước ([ hoặc -)
            elif key == ord("[") or key == ord("-") or key == ord("_"):
                roi_w = max(200, roi_w - resize_step)
                roi_h = max(200, roi_h - resize_step)
                resized = True

            if moved or resized:
                roi = (roi_x, roi_y, roi_w, roi_h)
                ui.roi = roi
                # Xóa kết quả nhận diện cũ để tránh lệch tọa độ trước khi frame mới được nạp
                last_persons = []
                last_seatbelts = []

    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
