# AppDetect – System Diagram
> **Nhánh hiện tại:** `dat` · **Commit mới nhất:** `ed506b1` – _"sua lai model person detect va seatbelt detect"_

---

## 1. Luồng khởi động app (App Startup Flow)

```mermaid
flowchart TD
    Launch([App Launch]) --> DVA

    subgraph DVA ["DriverVerifyActivity - LAUNCHER"]
        D1["Mở camera trước - ImageCapture"] --> D2["Nhấn Bat dau xac thuc"]
        D2 --> D3["Mỗi 2 giây: captureAndVerify()"]
        D3 --> D4["POST /api/verify\nGuii anh JPEG qua OkHttp"]
        D4 --> D5{verified?}
        D5 -->|"That bai"| D6["Hien thong bao loi\nThu lai sau 2s"]
        D6 --> D3
        D5 -->|"Loi server"| D7["Loi ket noi server\nThu lai sau 2s"]
        D7 --> D3
    end

    D5 -->|"verified = true"| MA

    subgraph MA ["MainActivity"]
        M1["Tai 3 AI models (background)"] --> M2["Nhan Bat dau chuyen di"]
        M2 --> M3["Xu ly tung frame\nSong song 4 luong"]
    end
```

---

## 2. Kiến trúc tổng quan (Component Diagram)

```mermaid
graph TD
    subgraph Backend ["☁️ Backend Server\n10.32.238.110:8000"]
        API["/api/verify\nFace Recognition API"]
    end

    subgraph App ["📱 Android App – nhánh: dat"]

        subgraph Screen1 ["🔐 DriverVerifyActivity (Entry Point)"]
            DVA["Camera trước\n→ chụp ảnh JPEG\n→ POST lên server mỗi 2s"]
        end

        subgraph Screen2 ["🚗 MainActivity"]
            MA["Điều phối chính\n4 ExecutorService"]

            subgraph Detectors ["🤖 AI Detectors (TFLite)"]
                PD["PersonDetector\nhuman_detection.tflite · 320px"]
                SD["SeatbeltDetector\nseatbelt_detection.tflite · 640px"]
                DD["DrowsinessDetector\nTFLite face + ML Kit"]
            end

            subgraph UI ["🖥️ UI"]
                BBO["BoundingBoxOverlay"]
                ST["StatusText / Icon"]
                RF["RedFlashOverlay"]
            end
        end
    end

    DVA -->|"OkHttp POST"| API
    API -->|"verified=true\n→ startActivity"| MA
    MA --> PD & SD & DD

    MA -->|runOnUiThread| UI
```

---

## 3. Luồng xử lý mỗi frame (Sequence Diagram)

```mermaid
sequenceDiagram
    participant Cam as 📷 Camera
    participant MA as MainActivity
    participant PE as personExecutor
    participant YE as seatbeltExecutor
    participant DE as drowsinessExecutor
    participant UI as 🖥️ UI Thread

    Cam->>MA: ImageProxy (frame mới)
    MA->>MA: imageProxyToBitmap()\n(rotate + flip → originalBitmap nét)
    MA->>MA: createScaledBitmap()\n(scale 480px → bitmap nhỏ)

    note over MA: Thu kết quả Future từ frame trước (nếu done)

    MA->>PE: submit detectPersons(bitmap nhỏ)
    PE-->>MA: Future·List·RectF (personBboxes)

    alt Có người được detect
        MA->>YE: submit detect(crop từ originalBitmap)
        note over YE: Tính tỉ lệ & Crop người từ originalBitmap\n(để giữ độ nét cho dây an toàn)\n→ SeatbeltDetector
        YE-->>MA: Future·List·Detection (seatbelt)
    else Không thấy người – fallback
        MA->>YE: submit detect(originalBitmap)
        YE-->>MA: Future·List·Detection (seatbelt)
    end

    MA->>DE: submit detect(bitmap nhỏ, timestamp)
    note over DE: TFLite face detect\n→ crop khuôn mặt\n→ ML Kit contour\n→ tính EAR / MAR / headYaw
    DE-->>MA: Future·DrowsinessResult

    MA->>UI: runOnUiThread → updateUI()
    UI->>UI: Vẽ BoundingBoxOverlay
    UI->>UI: Hiển thị cảnh báo theo priority
```

---

## 4. Logic cảnh báo (Priority Decision Flow)

```mermaid
flowchart TD
    A([Frame mới]) --> B{isFaceMissing?\n> 3s mất mặt}
    B -->|Yes| W1["🫥 CẢNH BÁO\nKHÔNG THẤY TÀI XẾ!\n🔴 Red Flash"]
    B -->|No| C{isDrowsy?\nEAR < 0.21 · > 3s}
    C -->|Yes| W2["😴 NGUY HIỂM\nĐANG NGỦ GẬT!\n🔴 Red Flash"]
    C -->|No| D{isDistracted?\nHeadYaw > 30° · > 3s}
    D -->|Yes| W3["🫣 CẢNH BÁO\nMẤT TẬP TRUNG!\n🔴 Red Flash"]
    D -->|No| E{isYawning?\nMAR > 0.7}
    E -->|Yes| W4["🥱 CẢNH BÁO\nĐANG NGÁP!\n🟠 Orange"]
    E -->|No| F{no-seatbelt\ndetected?}
    F -->|Yes| W5["⚠️ CẢNH BÁO\nKHÔNG ĐEO DÂY!\n🔴 Red"]
    F -->|No| G{seatbelt\ndetected?}
    G -->|Yes| W6["✅ AN TOÀN\nĐANG ĐEO DÂY\n🟢 Green"]
    G -->|No| W7["🔍 Đang theo dõi...\nHiện EAR / Yaw"]
```

---

## 5. Class Diagram

```mermaid
classDiagram
    class DriverVerifyActivity {
        -OkHttpClient httpClient
        -ImageCapture imageCapture
        -Handler handler
        -boolean isVerifying
        -boolean hasNavigated
        -long VERIFY_INTERVAL = 2000ms
        +onCreate()
        -captureAndVerify()
        -sendToApi(jpegBytes)
        -handleVerifyResult(response)
    }

    class VerifyResponse {
        +boolean verified
        +String driverId
        +float similarity
        +String message
    }

    class MainActivity {
        -YoloDetector detector
        -DrowsinessDetector drowsinessDetector
        -PersonDetector personDetector
        -ExecutorService x4
        +analyzeFrame(ImageProxy)
        -updateUI(detections, dResult, fps)
    }

    class PersonDetector {
        -Interpreter tflite
        -int INPUT_SIZE = 320
        -float CONF_THRESH = 0.35
        +detectPersons(Bitmap) List~RectF~
    }

    class SeatbeltDetector {
        -Interpreter tflite
        -int INPUT_SIZE = 640
        -float CONF_THRESH = 0.25
        +detect(Bitmap) List~Detection~
    }

    class DrowsinessDetector {
        -TFLiteFaceDetector tfliteDetector
        -FaceDetector mlkitDetector
        -float EAR_THRESHOLD = 0.21
        -float MAR_THRESHOLD = 0.7
        -long CLOSED_EYE = 3000ms
        -long DISTRACTION = 3000ms
        -long FACE_MISSING = 3000ms
        +detect(Bitmap, timestamp) DrowsinessResult
    }

    class DrowsinessResult {
        +boolean faceDetected
        +boolean isDrowsy
        +boolean isYawning
        +boolean isDistracted
        +boolean isFaceMissing
        +float ear
        +float mar
        +float headEulerY
        +RectF faceBbox
    }

    DriverVerifyActivity --> VerifyResponse : parses
    DriverVerifyActivity ..> MainActivity : startActivity (verified)
    MainActivity --> PersonDetector : uses
    MainActivity --> SeatbeltDetector : uses
    MainActivity --> DrowsinessDetector : uses
    DrowsinessDetector --> DrowsinessResult : produces
```
