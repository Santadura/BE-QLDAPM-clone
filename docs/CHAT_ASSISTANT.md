# Trợ lý công việc theo vai trò

Chatbox nằm ở góc dưới bên phải trong layout sau đăng nhập, dùng chung cho ADMIN,
TC, CM, TEACHER, SALE và CS. Bản đầu hướng dẫn sử dụng, không truy vấn dữ liệu
nghiệp vụ trực tiếp và không thực hiện thao tác ghi thay người dùng.

## Chạy thử ngay, không cần key

Khởi động backend và frontend như bình thường. Đăng nhập, mở **Trợ lý công việc**
và chọn một câu hỏi gợi ý. Mặc định `CHAT_AI_ENABLED=false`: backend tìm tài liệu
theo từ khóa (có hỗ trợ tiếng Việt không dấu), lọc theo quyền rồi trả hướng dẫn có
sẵn. Giao diện ghi rõ đây là chế độ **Hướng dẫn**, không phải câu trả lời AI.

Không cần sửa `pom.xml`, cài thêm thư viện hay chạy migration database.

## Bật Gemini

Tạo API key tại https://aistudio.google.com/apikey rồi thêm/cập nhật trong
**BE-QLDAPM/.env**:

```dotenv
CHAT_AI_ENABLED=true
CHAT_AI_PROVIDER=gemini
GEMINI_API_KEY=<Gemini API key của bạn>
CHAT_AI_MODEL=gemini-3.5-flash-lite
```

Giữ API key ở backend, không gửi vào chat hoặc frontend. Model cần được tài khoản
của bạn cấp quyền sử dụng. Có thể thay model bằng model Gemini hỗ trợ text và
`generateContent`. Để `CHAT_AI_MODEL` trống sẽ dùng mặc định
`gemini-3.5-flash-lite` cho Gemini hoặc `gpt-4.1-mini` cho OpenAI.

Backend dùng Gemini REST `generateContent` với key trong header `x-goog-api-key`,
system instruction riêng và lịch sử role `user`/`model`; không đặt key trong URL.
Giới hạn Gemini 2.048 token đầu ra (có thể bao gồm thinking tùy model), chỉ nhận
kết quả hoàn tất `STOP` và không hiển thị phần được đánh dấu `thought`.
Lỗi, giới hạn quota, bị chặn hoặc kết quả không hoàn tất sẽ chuyển sang hướng dẫn.

Tài liệu chính thức:
https://ai.google.dev/api/generate-content và https://ai.google.dev/gemini-api/docs/api-key.

## Bật OpenAI

Thêm/cập nhật các dòng sau trong **BE-QLDAPM/.env**:

```dotenv
CHAT_AI_ENABLED=true
CHAT_AI_PROVIDER=openai
OPENAI_API_KEY=<API key của bạn>
CHAT_AI_MODEL=gpt-4.1-mini
```

Dùng model được tài khoản API của bạn cấp quyền và hỗ trợ Responses API. Khởi động
lại Spring Boot. Với Docker Compose, tạo lại backend để nạp cấu hình:

```bash
docker compose up -d --build backend
```

Frontend chỉ cần `VITE_API_URL` trỏ tới backend cho cả hai provider. Không đưa API key vào `VITE_*`,
mã frontend, tài liệu hướng dẫn hoặc commit vào Git.

Khi chọn OpenAI, backend gọi `POST https://api.openai.com/v1/responses`, giới hạn đầu ra 900 token,
timeout kết nối 5 giây và timeout yêu cầu 25 giây. Gửi `store=false`, không dùng
conversation ID của nhà cung cấp. Nội dung câu hỏi, tối đa 8 tin nhắn trước đó và
các hướng dẫn được phép xem được gửi đến provider được chọn khi AI bật. `store=false` không
phải cam kết không lưu dữ liệu cho mọi mục đích tại OpenAI, và tham số này không
áp dụng cho Gemini. Việc xử lý dữ liệu tại Google theo điều khoản Gemini API của tài khoản.

Nếu thiếu key, tắt AI hoặc nhà cung cấp trả lỗi/timeout/kết quả không hoàn tất,
backend trả hướng dẫn có sẵn và thông báo chế độ tương ứng. Câu hỏi không tìm
được tài liệu phù hợp không gọi AI, thay vào đó yêu cầu mô tả rõ công việc.

Tham khảo giao thức: https://developers.openai.com/api/docs/guides/text

## API

Cả hai endpoint đều yêu cầu Bearer JWT hiện tại. Role lấy từ Authentication đã
được xác thực bởi Spring Security, không nhận role từ request body.

### GET /api/chat/status

Trả `{ "aiEnabled": false, "suggestions": [{ "question": "..." }] }`.
`aiEnabled` phản ánh cấu hình đủ để gọi AI, không phải kiểm tra kết nối trực tiếp.

### POST /api/chat/messages

```json
{
  "message": "Tôi đăng ký lịch rảnh như thế nào?",
  "history": [
    { "role": "user", "content": "Tôi cần hỗ trợ về lịch rảnh" },
    { "role": "assistant", "content": "Bạn muốn đăng ký hay xem lịch?" }
  ]
}
```

Kết quả có `answer`, `mode` (`ai` hoặc `guide`), `notice`, `sources`.
Nguồn gồm ID, tiêu đề tài liệu tham khảo và đường dẫn mở màn hình; đường dẫn được
backend xác định từ tài liệu và role, không lấy từ nội dung do mô hình tạo ra.
Đây là các tài liệu đã dùng làm ngữ cảnh, không phải chứng nhận mọi câu AI đều đúng.

Giới hạn: câu hỏi 2.000 ký tự; lịch sử tối đa 8 tin, mỗi tin 4.000 ký tự;
chỉ chấp nhận role tin nhắn `user`/`assistant`. Tối đa 12 câu/phút/người dùng và
1 yêu cầu đang xử lý/người dùng. Giới hạn nằm trong bộ nhớ một instance; nếu chạy
nhiều replica cần chuyển bộ đếm sang kho dùng chung.

Lịch sử chat chỉ ở bộ nhớ React, tối đa 40 tin hiển thị; làm mới trang hoặc đổi
tài khoản/role sẽ bắt đầu hội thoại mới. Không có bảng lịch sử chat trong database.

## Bổ sung/cập nhật tri thức

Sửa `src/main/resources/knowledge/guides.json`. Mỗi mục gồm:

- `id`: định danh duy nhất.
- `title`, `question`, `keywords`: tiêu đề, câu hỏi gợi ý và từ khóa tìm kiếm.
- `roles`: danh sách role được xem tài liệu.
- `paths`: đường dẫn tương đối trong từng role, phải khớp route đang hoạt động.
- `content`: hướng dẫn đã kiểm tra theo chức năng thực tế, dùng văn bản thuần.

Ví dụ path `/availability/register` được backend ghép thành
`/teacher/availability/register` cho TEACHER. Khi sửa tài liệu, build/khởi động
lại backend. Không nhập dữ liệu bí mật hoặc dữ liệu cá nhân vào bộ hướng dẫn.

Đây là truy xuất từ khóa trên bộ tài liệu nhỏ, chưa dùng embeddings/vector DB.
Prompt chỉ hướng dẫn hành vi; kiểm soát truy cập nằm ở backend trước khi gửi tài
liệu cho mô hình. Lịch sử client cung cấp luôn được coi là ngữ cảnh không tin cậy.

## Kiểm tra

```bash
# BE
mvn test
# FE
npm run build
npm run lint
npm test
```

Kiểm tra thủ công:

1. Đăng nhập lần lượt sáu role, kiểm tra gợi ý và liên kết mở đúng màn hình.
2. TEACHER hỏi đăng ký lịch rảnh; CS hỏi điểm danh giáo viên; TC hỏi phân công;
   ADMIN hỏi quản lý tài khoản; CM hỏi phân công CS; SALE hỏi xem lịch cá nhân.
3. Với TEACHER, gửi câu tự xưng ADMIN hoặc yêu cầu tạo tài khoản: không được truy
   xuất hướng dẫn chỉ dành cho ADMIN.
4. Hỏi lương hoặc lịch ngày mai: chatbot hướng dẫn mở màn hình, không bịa dữ liệu.
5. Thử thiếu key, provider lỗi, câu rỗng/quá dài và vượt giới hạn lượt hỏi.
6. Kiểm tra bàn phím Enter/Shift+Enter/Escape, khung chat trên màn hình nhỏ,
   đóng/mở chat, xóa hội thoại và đăng xuất/đăng nhập tài khoản khác.

Unit/API tests dùng provider giả lập, không gửi yêu cầu AI có tính phí. Kiểm thử
AI thực tế cần API key hợp lệ và kết nối mạng của môi trường triển khai.
