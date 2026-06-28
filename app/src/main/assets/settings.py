"""
Cấu hình chung cho hệ thống AI
"""
import os

# Thư mục chứa file settings.py (= thư mục assets)
_ASSETS_DIR = os.path.dirname(os.path.abspath(__file__))

# Drowsiness Detection
EAR_THRESHOLD = 0.2
SLEEP_FRAME_LIMIT = 20

# Person Detection
PERSON_CONFIDENCE = 0.5
MIN_DETECTION_SIZE = 30

# Seatbelt Detection
# Dùng 0.25 thay vì 0.5 vì model train trên ảnh trong xe,
# khi test ngoài xe confidence tự nhiên thấp hơn
SEATBELT_CONFIDENCE = 0.25

# Model Paths - luôn dùng đường dẫn tuyệt đối tính từ thư mục assets
YOLO_PERSON_MODEL = os.path.join(_ASSETS_DIR, "weights", "yolo", "person_detection.onnx")
YOLO_SEATBELT_MODEL = os.path.join(_ASSETS_DIR, "best.pt")
YOLO_SEATBELT_TFLITE = os.path.join(_ASSETS_DIR, "best.tflite")
INSIGHTFACE_MODEL = os.path.join(_ASSETS_DIR, "weights", "face", "insightface.onnx")
FACEMESH_MODEL = os.path.join(_ASSETS_DIR, "weights", "face", "facemesh.task")
AGE_CLASSIFIER_MODEL = os.path.join(_ASSETS_DIR, "weights", "classification", "age_classifier.onnx")

# Output Paths
SCREENSHOT_OUTPUT = "data/outputs/screenshots/"
RECORDING_OUTPUT = "data/outputs/recordings/"
LOG_OUTPUT = "data/outputs/logs/"

# Face Recognition
FACE_EMBEDDING_DIM = 512
FACE_MATCH_THRESHOLD = 0.6

# Video Input
INPUT_VIDEO_PATH = "data/sample_videos/"
KNOWN_FACES_PATH = "data/known_faces/"

# UI Settings
FPS_DISPLAY = True
SHOW_LANDMARKS = True
SHOW_BBOXES = True
