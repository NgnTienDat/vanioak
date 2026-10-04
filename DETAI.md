## Hệ thống Thu thập log và Giám sát Lỗi Ứng dụng phục vụ môi trường DEV/TEST

"Phân hệ Quản lý Bản ghi Log (Log Asset Management): Quản lý cấu trúc dữ liệu của một bản ghi log bao gồm: Application_Name, Log_Level (INFO, WARN, ERROR, CRITICAL), Message, Timestamp, và Trace_ID. Quản lý trạng thái xử lý log (Log thô vừa nhận, Đã chuẩn hóa, Đã lưu trữ).

Phân hệ Ma trận Tiếp nhận Luồng Log (High-Speed Ingestion Matrix): Thiết kế một API chịu tải cao để các phần mềm khác liên tục bắn log về (ví dụ: cứ mỗi hành động của user ở app khác là bắn 1 log). Hệ thống bắt buộc phải đẩy toàn bộ dữ liệu log thô này vào Message Queue làm bộ đệm để cân bằng tải, vì nếu mỗi bản ghi log đổ về đều thực hiện câu lệnh INSERT trực tiếp xuống DB SQL, DB sẽ bị quá tải và sập trong vòng vài phút.

Phân hệ Engine Chuẩn hóa & Bộ lọc Sự cố (Log Parsing & Filtering Engine): Các Worker consume dữ liệu từ Message Queue, thực hiện bóc tách ký tự, làm sạch dữ liệu và thực hiện lưu trữ xuống DB. Nếu Worker phát hiện bản ghi log có chứa Level là ERROR hoặc CRITICAL, hệ thống lập tức kích hoạt một Event cảnh báo nguy cấp chuyển sang hàng đợi ưu tiên.

Phân hệ Điều phối Cảnh báo & Khóa Trùng (Alert Locking Mechanism): Khi nhận được Event lỗi nguy cấp, hệ thống tự động đẩy thông báo thời gian thực qua WebSocket lên màn hình giám sát của kỹ sư vận hành và gửi tin nhắn về Telegram. Áp dụng cơ chế khóa trùng cảnh báo (Alert Deduplication) bằng Redis: Nếu một lỗi xuất hiện liên tiếp 100 lần trong 1 phút, hệ thống chỉ phát 1 thông báo duy nhất nhằm tránh gây tràn ngập (Alert Fatigue) cho kỹ sư.

Phân hệ Quản trị trực quan (Real-time Log Viewer): Giao diện màn hình hiển thị luồng log chạy liên tục theo thời gian thực (Live Stream View), hỗ trợ bộ lọc nhanh theo loại ứng dụng hoặc cấp độ lỗi mà không cần reload trang.

Tính năng bổ sung: Phân quyền hiển thị (Kỹ sư chỉ xem được log của ứng dụng mình quản lý; Admin hệ thống hệ thống có quyền cấu hình ngưỡng báo động)."
"Tài liệu kỹ thuật ngắn gọn: Sơ đồ kiến trúc luồng dữ liệu Log đi qua Message Queue, thiết kế cơ sở dữ liệu tối ưu cho việc ghi nhanh, tài liệu hướng dẫn API.

### Ứng dụng cần đáp ứng:

Đóng gói được và triển khai bằng Docker (Dockerfile / Docker-compose) - bao gồm Backend, DB, Message Queue và Redis Cache.

Đầy đủ chức năng yêu cầu theo các phân hệ đã mô tả.

Demo một quy trình đơn giản: Giả lập chạy tool bắn 500 log liên tục trong 2 giây vào hệ thống -> Hệ thống tiếp nhận không lỗi -> Giao diện admin hiển thị log đổ về mượt mà.

Điểm cộng:

Tính năng tự động dọn dẹp log cũ (Log Retention Policy): Định kỳ chạy Job ngầm tự động xóa hoặc nén các bản ghi log hệ thống dạng INFO đã quá 7 ngày để giải phóng dung lượng cho ổ cứng.
AI phân tích, phân loại log

Báo cáo Thống kê Sức khỏe App (Application Health Analytics): Thống kê và vẽ biểu đồ tỷ lệ lỗi giữa các ứng dụng theo giờ để biết hệ thống nào đang kém ổn định nhất." 
