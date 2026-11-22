# Backend GK — Spring Boot + PostgreSQL + JWT

## Trợ lý công việc

Chatbox hướng dẫn theo role đã tích hợp qua `/api/chat/status` và
`/api/chat/messages`. Chạy được bằng hướng dẫn có sẵn, có thể bật AI bằng cấu hình
backend. Xem [cách cấu hình và kiểm tra](docs/CHAT_ASSISTANT.md).

## Chạy toàn bộ BE bằng Docker (khuyến nghị)

Máy mới chỉ cần **Git và Docker Desktop (Docker Compose v2)**. Không cần cài PostgreSQL, Node.js, Prisma, Java hoặc Maven ở máy host.

```powershell
git clone https://github.com/Santadura/BE-QLDAPM-clone.git
cd BE-QLDAPM-clone
# Khi cấu hình chưa được merge vào main: git switch luan
docker compose up -d --build
```

Lần chạy đầu sẽ tự động thực hiện đúng thứ tự:
1. PostgreSQL 16 tạo database `qlapm_db` và chờ healthcheck.
2. Service `db-init` chạy `prisma migrate deploy`, sau đó **chỉ seed khi database trống**. Service này kết thúc thành công.
3. Service `backend` chạy Spring Boot (Java 17) trên `http://localhost:8080`.

Dữ liệu PostgreSQL nằm trong Docker volume `qlapm_pgdata`; chạy lại `docker compose up -d --build` không xóa dữ liệu hoặc ghi đè dữ liệu từ seed. Nếu database đã có dữ liệu, bước seed demo sẽ bị bỏ qua.

Kiểm tra trạng thái và log:

```powershell
docker compose ps -a
docker compose logs --tail=100 db-init
docker compose logs -f backend
```

Dừng container nhưng giữ dữ liệu: `docker compose down`.

**Cảnh báo:** `docker compose down -v` **xóa vĩnh viễn toàn bộ database local** trong volume. Chỉ dùng khi thực sự muốn làm lại dữ liệu từ đầu.

Cấu hình Docker này dành cho **development**: PostgreSQL mở cổng 5432, user/password mặc định `postgres/postgres`, dữ liệu mẫu có mật khẩu thử nghiệm. Không deploy nguyên cấu hình này lên production. Đổi `JWT_SECRET` và database credentials trước khi triển khai thật.

**Lưu ý:** Nếu port 5432 hoặc 8080 đang bị chiếm (PostgreSQL/Spring Boot trên host), hãy dừng tiến trình cũ trước khi chạy Compose. Container kết nối DB qua hostname `postgres`, không phải `localhost`.

## Chạy BE trực tiếp (để debug ngoài Docker)

Yêu cầu: Java 17, Maven 3.9+, Node.js 20+, Docker hoặc PostgreSQL cài sẵn.

```powershell
docker compose up -d postgres
Copy-Item .env.example .env
npm ci
npx prisma migrate deploy
npm run db:seed
mvn spring-boot:run
```

Ở cách chạy thủ công, Spring Boot dùng cấu hình trong `.env` và database tại `localhost:5432`. Lệnh `npm run db:seed` **chạy trực tiếp** và có thể cập nhật dữ liệu mẫu; chỉ chạy khi thật sự cần.

## Các API có sẵn

| Method | Endpoint | Auth | Mô tả |
|---|---|---|---|
| POST | `/api/auth/register` | Không | Đăng ký user mới |
| POST | `/api/auth/login` | Không | Đăng nhập, trả về JWT |
| GET | `/api/public/ping` | Không | Test server sống |
| GET | `/api/dashboard` | Có | Tổng quan theo role của tài khoản hiện tại (ADMIN, TC, CM, TEACHER, SALE, CS) |
| GET | `/api/statistics?period=YEAR&year=2026` | ADMIN | Tổng hợp học viên, lớp, nhân sự, lịch phân công và công lương |
| GET | `/api/me` | **Có** (Bearer token) | Lấy thông tin user hiện tại |

Dashboard trả thống kê, lịch trong ngày, hoạt động gần đây và mục cần xử lý từ dữ liệu lớp học, học viên, lịch dạy/làm, lịch rảnh và bài tập. Role được đọc từ tài khoản đã xác thực; endpoint không nhận role từ client.

API thống kê hỗ trợ `period=DAY&date=2026-10-19`, `period=MONTH&year=2026&month=10` và `period=YEAR&year=2026`. Response gồm các nhóm `overview`, `employees`, `schedules`, `financial` và `monthly`. Số học viên/lớp/nhân sự, học viên mới, trạng thái lịch và xung đột phân công được tổng hợp từ các bảng vận hành; chi phí lương lấy từ `employee_salaries` và `staff_salaries`. Do hệ thống chưa có dữ liệu giao dịch/học phí, `financial.revenue` và doanh thu theo tháng trả `null` kèm `revenueAvailable=false` thay vì số liệu ước tính. Bảng lương theo tháng nên các trường chi phí lương trả `null` khi lọc theo ngày. API chỉ cho phép tài khoản ADMIN đang hoạt động.

### Ví dụ đăng ký
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","email":"admin@test.com","password":"123456"}'
```

### Ví dụ đăng nhập
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```
Response trả về `token` — dùng token này gắn vào header `Authorization: Bearer <token>` cho các API cần đăng nhập.

### Gọi API cần token
```bash
curl http://localhost:8080/api/me \
  -H "Authorization: Bearer <token_vua_nhan_duoc>"
```

## Cấu trúc thư mục
```
src/main/java/com/example/demo/
├── config/          # SecurityConfig (CORS, filter chain)
├── security/         # JwtUtil, JwtAuthFilter
├── entity/           # User (JPA entity)
├── repository/        # UserRepository
├── dto/               # Request/Response DTOs
├── service/           # AuthService, CustomUserDetailsService
├── controller/        # AuthController, TestController
└── exception/          # ApiException, GlobalExceptionHandler
```

## Kết nối với FE (React + Vite)
Trong file `.env` của FE, set:
```
VITE_API_URL=/api
```
CORS trong `SecurityConfig.java` cho phép website bán hàng tại `http://localhost:5173` và trang quản lý tại `http://localhost:5174`, cùng các địa chỉ `127.0.0.1` tương ứng.

## Thương hiệu GK

Tên ứng dụng và hướng dẫn trợ lý đã đồng bộ thành GK Academy. Dữ liệu seed mới
sử dụng email `@gk.local`; tài khoản và dữ liệu đã có trong database không tự đổi
email khi cập nhật mã nguồn. Không cần xóa hoặc tạo lại database để đổi giao diện.

## Đăng nhập / đăng ký Google và Facebook

### Chuẩn bị và chạy local

Luồng này dành cho **học viên (USER)** ở web bán hàng, không cấp quyền quản lý.
Lần đầu xác thực thành công sẽ tạo tài khoản GK và lưu liên kết nhà cung cấp;
lần sau dùng lại tài khoản đó. Google/Facebook vẫn có thể yêu cầu chọn tài khoản,
đăng nhập hoặc đồng ý quyền theo phiên đăng nhập của họ.

Migration `20261120140000_social_auth` bổ sung `users.password_login_enabled`,
`users.display_name`, bảng `social_identities` và `social_login_tickets`.
Migration này đã được áp dụng trên database local khi triển khai tính năng.
Với database khác, chạy trong thư mục backend:

```bash
npx prisma migrate deploy
```

Không reset database hoặc chạy lại seed để bật tính năng này.
FE nên dùng `VITE_API_URL=/api` (hoặc để trống), Vite chuyển `/api` sang BE8080.
OAuth callback đi qua **cổng web bán hàng 5173**, không phải cổng quản lý 5174.
Dùng nhất quán `localhost`; không đổi qua `127.0.0.1` giữa luồng đăng nhập.

### 1. Tạo Google OAuth client

1. Mở [Google Cloud Console](https://console.cloud.google.com/), tạo/chọn project.
2. Vào Google Auth Platform, cấu hình Branding và Audience. Khi thử nghiệm,
   thêm email của bạn vào Test users nếu ứng dụng đang ở chế độ Testing.
3. Trong Clients, tạo OAuth client loại **Web application**.
4. Thêm Authorized redirect URI chính xác:
   `http://localhost:5173/api/oauth2/callback/google`.
   Nếu nhập Authorized JavaScript origins, dùng `http://localhost:5173`.
5. Lưu Client ID và Client Secret vào `.env` backend theo mẫu bên dưới.

Ứng dụng chỉ xin `openid`, `email`, `profile`; không cần quyền Drive/Gmail.
Tham khảo [hướng dẫn OAuth web server chính thức của Google](https://developers.google.com/identity/protocols/oauth2/web-server).

### 2. Tạo Facebook app

1. Mở [Meta for Developers](https://developers.facebook.com/apps/), tạo app
   có use case đăng nhập Facebook. Thêm/cấu hình Facebook Login cho website.
2. Lấy App ID và App Secret từ phần cài đặt app; điền vào backend.
3. Trong Facebook Login → Settings, bật Client OAuth Login và Web OAuth Login,
   khai báo Valid OAuth redirect URI:
   `http://localhost:5173/api/oauth2/callback/facebook`.
4. Khi app còn Development, thử bằng tài khoản có vai trò developer/tester của app.
   Luồng dùng `public_profile` và `email`; nếu Facebook không trả email, người dùng
   cần đăng ký GK bằng email rồi liên kết Facebook trong hồ sơ.
5. Nếu Meta yêu cầu HTTPS cho cấu hình app của bạn, dùng một origin HTTPS phát triển
   có thể truy cập, cập nhật `SOCIAL_FRONTEND_URL` và callback tương ứng ở Meta.
   Origin đó phải phục vụ cả FE và proxy `/api`. Đặt `SESSION_COOKIE_SECURE=true`.
   Khi đưa app ra công khai, hoàn tất các yêu cầu về quyền, chính sách riêng tư
   và xóa dữ liệu mà dashboard Meta hiển thị.

Tên mục trên dashboard có thể khác tùy loại app. Tham khảo
[hướng dẫn Facebook Login phía server của Meta](https://developers.facebook.com/docs/facebook-login/guides/advanced/manual-flow/).

### 3. Điền cấu hình và khởi động lại

Thêm vào **BE-QLDAPM/.env**, không phải `.env` FE:

```dotenv
SOCIAL_FRONTEND_URL=http://localhost:5173
GOOGLE_CLIENT_ID=client-id-cua-ban
GOOGLE_CLIENT_SECRET=client-secret-cua-ban
FACEBOOK_CLIENT_ID=app-id-cua-ban
FACEBOOK_CLIENT_SECRET=app-secret-cua-ban
FACEBOOK_API_VERSION=v26.0
SESSION_COOKIE_SECURE=false
```

Có thể cấu hình từng nhà cung cấp riêng. Để trống cả ID/Secret của bên chưa dùng.
Không đưa secret vào `VITE_*`, commit Git hoặc gửi trong hội thoại.
Khởi động lại backend sau khi đổi `.env`; nếu chạy Docker, rebuild/recreate
service `backend` để nhận mã và biến môi trường mới. FE chạy `npm run dev`
trong thư mục FE, mở `http://localhost:5173/user/login`.

Kiểm tra `http://localhost:5173/api/auth/social/providers`: nhà cung cấp có đủ
cấu hình sẽ trả `true`. Nút tương ứng sẽ bật. Giá trị `true` chỉ xác nhận đã có
ID/Secret, chưa chứng minh khóa hợp lệ; cần thử đăng nhập thật sau khi tạo app.
Nếu lỗi `redirect_uri_mismatch`, kiểm tra lại origin, cổng và callback từng ký tự.
Nếu báo state/phiên hết hạn, bắt đầu lại từ nút đăng nhập trên web GK.

### Tài khoản đã có và cách liên kết

- Tài khoản GK đã có email: đăng nhập bằng mật khẩu → Tài khoản → Hồ sơ →
  **Đăng nhập nhanh** → Liên kết Google/Facebook. Sau đó dùng nút mạng xã hội
  để đăng nhập vào cùng tài khoản. Không tự động gộp theo email.
- Tài khoản tạo bằng mạng xã hội không có mật khẩu GK để đăng nhập thường.
- Đăng xuất xóa phiên GK, không đăng xuất tài khoản Google/Facebook trên trình duyệt.
- Chỉ lưu ID nhà cung cấp, email hồ sơ và liên kết tài khoản; không lưu access token
  hoặc refresh token của Google/Facebook vào database.

### Kiểm tra và các endpoint

`GET /api/auth/social/providers`, `POST /api/auth/social/start`,
`POST /api/auth/social/exchange`, `GET /api/auth/social/connections`.
OAuth authorization/callback nằm trong `/api/oauth2/**`.
Phiên OAuth kiểm tra state; Google thêm OIDC nonce/PKCE. Sau callback, FE đổi mã
một lần (120 giây, ràng buộc verifier của trình duyệt) lấy JWT GK. JWT không nằm
trong URL. Liên kết tài khoản cần JWT USER còn hiệu lực.

Chạy unit test bằng `mvn test`. Kiểm tra schema thực bằng
`GK_SOCIAL_DB_TEST=true mvn -Dtest=SocialDatabaseIntegrationTest test` trên database
thử nghiệm có migration; test chạy transaction rollback. Các test dùng danh tính
nhà cung cấp giả lập, không thay thế kiểm tra Google/Facebook thật với app của bạn.

## Đồng bộ website bán khóa học với PostgreSQL

Migration `20261120170000_storefront_sync` đã áp dụng trên DB local, không reset
hoặc thay thế tài khoản/dữ liệu quản lý cũ. Bốn khóa nổi bật và sáu lịch giới thiệu
trước đây của web được đưa vào DB làm dữ liệu khởi tạo; năm lớp còn mở đăng ký.
Các lớp này có lịch buổi học ban đầu trong `class_sessions`. Nhân viên cần kiểm tra
lại học phí, lịch và sức chứa trước khi sử dụng vận hành thực tế.
Các khóa ACTIVE có sẵn trong DB cũng xuất hiện trên web; khóa thiếu học phí hiển thị
“Liên hệ học phí”, không dùng giá mẫu thay thế.

Dữ liệu dùng chung:

| Dữ liệu | Nơi lưu |
| --- | --- |
| Đăng nhập, hồ sơ và liên kết mạng xã hội | `users`, `social_identities` |
| Tên, học phí, ảnh, mô tả, đầu vào, nội dung và trạng thái hiển thị khóa học | `courses` |
| Lớp, khai giảng, sức chứa, hình thức và mở đăng ký | `classes`, `class_sessions` |
| Giỏ hàng đã đăng nhập | `storefront_cart_items` |
| Đơn đăng ký, học phí tại thời điểm đăng ký | `storefront_orders`, `storefront_order_items` |
| Hồ sơ học viên liên kết và danh sách lớp | `students.username`, `class_students` |
| Yêu cầu tư vấn | `storefront_consultations` |

Giỏ khách vẫn lưu trên trình duyệt. Sau đăng nhập, học viên có thể bấm **Nhập giỏ
khách vào tài khoản** tại trang giỏ hàng; chỉ các lớp còn mở được nhập, mục không
hợp lệ được giữ để người dùng xem/xóa. Không chuyển các giao dịch mô phỏng cũ
trong localStorage thành ghi danh hoặc thanh toán thật.

### Quản lý dữ liệu website

Đăng nhập cổng quản lý bằng tài khoản Admin của bạn → **Website khóa học**
(`/admin/storefront`). Có bốn tab: khóa học, lớp mở đăng ký, đơn đăng ký, tư vấn.
Có thể thêm khóa học mới hoặc chỉnh sửa khóa có sẵn. Lưu tại đây sẽ cập nhật DB. Web bán hàng đọc lại khi chuyển trang,
tải lại hoặc quay lại cửa sổ; không cần sửa file dữ liệu FE.

- Khóa học: chỉ `ACTIVE` và `published=true` được đưa lên web. `featured=true`
  hiển thị ở trang chủ. Học phí có thể để trống. Ảnh là đường dẫn tài nguyên
  `/images/...` của frontend. Trang quản lý lớp hiện có dùng cùng tên khóa học.
- Lớp: chỉ `READY`, `storefront_open=true`, chưa qua ngày khai giảng mới xuất hiện
  trong lịch đăng ký công khai. Lớp cần học phí, địa điểm và lịch hiển thị để nhận
  đăng ký. Ngày và thời khóa biểu thực tế chỉnh trong quản lý lớp; lịch hiển thị
  là nhãn tóm tắt, không tự thay thế các buổi `class_sessions`.
- Lớp của học viên: web lấy từ `class_students` của hồ sơ liên kết, bao gồm
  cả ghi danh do quản lý thêm trực tiếp, không chỉ các đơn đặt trên web.
- Đơn: gửi từ web tạo `PENDING`, giữ số chỗ. Học viên có thể hủy; Admin có thể
  từ chối để giải phóng chỗ. Xác nhận chuyển `CONFIRMED` và thêm học viên vào
  `class_students` qua dịch vụ kiểm tra điều kiện đầu vào/trùng lịch hiện có.
- Học viên được liên kết bằng `students.username`, không tự ghép theo email.
  Sửa hồ sơ liên hệ ở web cập nhật hồ sơ học viên liên kết; sửa học viên ở quản lý
  cập nhật tên/email liên hệ/điện thoại của tài khoản liên kết. Email đăng nhập
  giữ riêng, không đổi khi chỉnh email liên hệ.

**Thanh toán:** web tích hợp VNPAY/MoMo theo mục sandbox bên dưới, cần khóa merchant
và IPN HTTPS trước khi sử dụng. Xác nhận ghi danh thủ công không tự đánh dấu đã
thu tiền; kết quả thanh toán và ghi danh được lưu riêng.

### API và xác thực

- Công khai: `GET /api/store/catalog`, `POST /api/store/consultations`.
- Học viên `USER`: `/api/store/me/profile`, `/cart`, `/orders`, `/enrollments` và hủy đơn của mình.
- Chỉ `ADMIN`: `/api/store/admin/catalog`, `/courses/{id}`, `/classes/{id}`,
  `/orders`, xác nhận/từ chối đơn và `/consultations`.

Backend lấy username từ JWT, không từ body; kiểm tra giá/lớp/sĩ số trong transaction,
khóa hàng để tuần tự hóa cập nhật, và chống gửi lặp bằng request key. Đơn ghi lại
học phí và tên khóa học lúc đăng ký. Giỏ hàng không giữ chỗ.

Sau cập nhật mã, chạy migration ở môi trường khác bằng `npx prisma migrate deploy`
rồi **khởi động lại backend**. Với Docker cần rebuild/recreate service `backend`.
FE dùng proxy `/api` như phần hướng dẫn trên.

Kiểm tra: `GK_STORE_DB_TEST=true GK_SOCIAL_DB_TEST=true mvn test` chạy thêm bài
kiểm tra PostgreSQL trong transaction rollback với dữ liệu tự tạo. Không dùng
tài khoản quản trị có sẵn cho kiểm tra tự động.

## Thanh toán VNPAY / MoMo (sandbox)

Web có luồng: chọn lớp → thông tin học viên → chọn VNPAY/MoMo → tạo giao dịch →
hiển thị QR/nút mở cổng → đợi backend xác nhận → thông báo hoàn tất.
**Chưa có khóa merchant thì chưa thể thực hiện giao dịch sandbox thật.**
Không có nút hoặc endpoint tự đánh dấu “đã thanh toán” cho frontend.

### 1. Lấy bộ khóa thử nghiệm

**VNPAY:** ngày 20/11/2026, kiểm tra trực tiếp cả `/devreg` và `/devreg/`
trả về HTTP 404 dù tài liệu vẫn dẫn đến đó. Liên hệ qua
[trang hỗ trợ VNPAY](https://sandbox.vnpayment.vn/apis/contact/) để xin cấp tài khoản
sandbox, `TmnCode`, `HashSecret` và hướng dẫn tài khoản/thẻ test.
Không coi bộ thông tin thẻ test là bộ khóa merchant. Khai báo IPN URL theo mục 2 trong tài khoản sandbox
hoặc nhờ bộ phận hỗ trợ sandbox thiết lập. Tham khảo
[tài liệu kết nối chính thức VNPAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).

**MoMo:** làm theo [quy trình tích hợp MoMo](https://developers.momo.vn/v3/vi/docs/payment/onboarding/integration-process/)
để tạo tài khoản merchant/MoMo Business tại [business.momo.vn](https://business.momo.vn/)
(theo [hướng dẫn đăng ký M4B](https://developers.momo.vn/v3/vi/docs/payment/onboarding/merchant-profile/)) và lấy bộ `Partner Code`, `Access Key`,
`Secret Key` của **môi trường thử nghiệm**. Dùng ứng dụng/tài khoản test theo
[hướng dẫn MoMo](https://developers.momo.vn/v3/vi/download/).
Luồng dùng `captureWallet`, `autoCapture=true`; chỉ kết quả IPN hợp lệ với
`resultCode=0` được ghi nhận đã trả tiền. Xem
[API thanh toán thông thường](https://developers.momo.vn/v3/vi/docs/payment/api/wallet/onetime/).

Không dùng khóa công khai sao chép từ bài hướng dẫn bên ngoài. Không đưa khóa
merchant vào FE, biến `VITE_*`, Git hoặc hội thoại.

### 2. Địa chỉ nhận kết quả từ nhà cung cấp

`localhost` chỉ truy cập được trên máy của bạn. Để nhận IPN, cần một origin HTTPS
công khai trỏ vào backend 8080 (qua môi trường phát triển public/reverse proxy
hoặc tunnel do bạn cấu hình). Ví dụ origin `https://your-dev-domain.example`:

| Mục | URL |
| --- | --- |
| VNPAY IPN (khai báo phía VNPAY) | `https://your-dev-domain.example/api/payments/vnpay/ipn` |
| MoMo IPN (backend tự gửi khi tạo giao dịch) | `https://your-dev-domain.example/api/payments/momo/ipn` |
| VNPAY Return (backend tự gửi) | `http://localhost:5173/api/payments/vnpay/return` |
| MoMo Redirect (backend tự gửi) | `http://localhost:5173/api/payments/momo/return` |

Origin public phải chuyển tiếp `/api/payments/**` vào backend; không cần đăng nhập
GK đối với callback. Nếu tunnel/reverse proxy có trang chặn trình duyệt, cần bảo đảm
máy chủ nhà cung cấp gọi được IPN mà không qua màn hình xác nhận.
Không tự triển khai tunnel hoặc public dự án chỉ bằng cách điền URL mẫu.
Nếu nhà cung cấp yêu cầu Return URL public, cấu hình origin HTTPS phục vụ **cả FE
và proxy `/api`**, rồi đổi `PAYMENT_FRONTEND_URL` cho khớp. Luôn giữ một origin FE
nhất quán để phiên đăng nhập còn tồn tại khi quay về.

### 3. Điền BE-QLDAPM/.env và khởi động lại

```dotenv
PAYMENT_SANDBOX=true
PAYMENT_FRONTEND_URL=http://localhost:5173
PAYMENT_PUBLIC_API_URL=https://your-dev-domain.example

VNPAY_TMN_CODE=
VNPAY_HASH_SECRET=

MOMO_PARTNER_CODE=
MOMO_ACCESS_KEY=
MOMO_SECRET_KEY=
```

Thay origin mẫu bằng origin HTTPS của bạn và điền các khóa nhận từ nhà cung cấp.
Có thể bật từng bên riêng. Chỉ khi có đủ khóa và origin callback hợp lệ, phương thức
mới hiện `enabled=true` tại `GET /api/payments/providers`.
Khởi động lại tiến trình backend đang chạy bằng `Ctrl+C` rồi `mvn spring-boot:run`;
không chạy thêm bản thứ hai cùng cổng 8080. Nếu dùng Docker, rebuild/recreate backend
sau khi đổi code hoặc `.env`.

Migration `20261120183000_gateway_payments` đã được áp dụng trên DB local, thêm
`payment_attempts` và trạng thái thanh toán trên `storefront_orders`. Môi trường khác
chạy `npx prisma migrate deploy`. Đơn cũ vẫn `UNPAID`; không tự biến dữ liệu demo
thành giao dịch đã trả tiền.

### Kết quả, ghi danh và xử lý lỗi

- Giá lấy từ đơn DB, không nhận giá từ trình duyệt. Mỗi đơn chỉ có một giao dịch
  đang tạo/chờ còn hiệu lực; backend tái sử dụng giao dịch khi bấm lặp.
- QR VNPAY trên web mã hóa URL mở cổng VNPAY; phương thức ngân hàng/VNPAY-QR chọn
  tại cổng. QR MoMo dùng `qrCodeUrl` do MoMo trả (dữ liệu QR, không phải ảnh),
  hoặc URL mở cổng nếu không được cấp dữ liệu QR. QR được tạo cục bộ trên FE.
- Thời hạn hiển thị trên web là 15 phút; nhà cung cấp cũng kiểm soát thời hạn riêng.
  Thông báo thành công hợp lệ đến muộn vẫn được ghi nhận, kể cả mã đã hết hạn local.
- Return/Redirect chỉ đưa người dùng về trang kết quả, **không cập nhật đã thanh toán**.
  Chỉ IPN hợp lệ mới cập nhật `PAID`. FE đọc trạng thái DB mỗi 3 giây khi chờ.
  Nếu đã trả tiền nhưng IPN chưa tới, bấm kiểm tra trạng thái/liên hệ trung tâm;
  không tiếp tục trả thêm lần nữa. Chưa có chức năng truy vấn đối soát hoặc hoàn tiền
  tự động từ nhà cung cấp trong phiên bản này.
- Xác nhận kiểm tra chữ ký HMAC, merchant, mã giao dịch, số tiền và chống xử lý lặp.
  Sau khi lưu tiền thành công, hệ thống ghi danh qua kiểm tra đầu vào, trùng lịch
  và sĩ số hiện có. Lỗi ghi danh không rollback trạng thái tiền đã nhận; đơn hiện
  yêu cầu trung tâm kiểm tra. Thanh toán vào đơn đã hủy hoặc trùng giao dịch cũng
  cần trung tâm xử lý/đối soát hoàn tiền thủ công.
- Không được hủy/từ chối đơn đã trả tiền hoặc đang có mã thanh toán hiệu lực.
  Admin vẫn có thể xác nhận ghi danh thủ công theo luồng quản lý hiện có;
  trạng thái ghi danh và trạng thái đã thanh toán luôn tách riêng.

Cơ chế IPN tham khảo [MoMo Payment Notification](https://developers.momo.vn/v3/docs/payment/api/result-handling/notification/).

### Kiểm tra

`GK_PAYMENT_DB_TEST=true GK_STORE_DB_TEST=true GK_SOCIAL_DB_TEST=true mvn test`
kiểm tra DB và callback có chữ ký với khóa thử nghiệm nội bộ, không gửi tiền hoặc
xác thực bằng tài khoản merchant thật. Phần lớn test rollback; test riêng về lỗi
ghi danh dùng transaction tách biệt và dọn dữ liệu đã tạo sau khi chạy.
Cần kiểm tra round-trip với VNPAY/MoMo sandbox thật sau khi có tài khoản/khóa và
IPN HTTPS hoạt động. Không thay bộ kiểm tra giả lập bằng khẳng định đã kết nối thật.

### Biến MoMo riêng và tạo URL IPN thử nghiệm

Backend cũng hỗ trợ các biến sau để dùng với cấu hình MoMo riêng:

```dotenv
MOMO_ENDPOINT=https://test-payment.momo.vn/v2/gateway/api/create
MOMO_REDIRECT_URL=http://localhost:5173/api/payments/momo/return
MOMO_IPN_URL=https://your-assigned-domain.ngrok-free.dev/api/payments/momo/ipn
```

`MOMO_ENDPOINT` phải khớp môi trường `PAYMENT_SANDBOX`; không dùng endpoint không
thuộc MoMo. Redirect dùng đúng origin frontend và đường dẫn callback của dự án.
Không dùng các đường dẫn mẫu `/payment/momo/callback` hoặc cổng 3000 nếu web đang
chạy ở 5173. Nếu `MOMO_IPN_URL` để trống, backend lấy
`PAYMENT_PUBLIC_API_URL` + `/api/payments/momo/ipn`.
Nếu đã có IPN HTTPS đầy đủ, MoMo không cần biến origin public chung để bật.

Nếu chưa có domain thử nghiệm:

1. Đăng ký tài khoản và tải ngrok theo [hướng dẫn chính thức](https://ngrok.com/docs/start).
2. Lấy authtoken trong dashboard rồi chạy trên máy của bạn:
   `ngrok config add-authtoken YOUR_NGROK_AUTHTOKEN`. Không gửi token vào hội thoại.
3. Giữ backend hoạt động ở cổng 8080. Mở terminal khác chạy:
   `ngrok http 8080`.
4. Sao chép địa chỉ **HTTPS** trong dòng Forwarding mà ngrok cấp. Giữ terminal
   ngrok chạy trong lúc thử thanh toán. Ví dụ ngrok cấp
   `https://your-assigned-domain.ngrok-free.dev`, điền vào `.env`:

   ```dotenv
   PAYMENT_PUBLIC_API_URL=https://your-assigned-domain.ngrok-free.dev
   MOMO_IPN_URL=https://your-assigned-domain.ngrok-free.dev/api/payments/momo/ipn
   ```

5. Dừng backend cũ bằng Ctrl+C rồi chạy lại `mvn spring-boot:run`.
   Mở `http://localhost:5173/api/payments/providers` để kiểm tra `MOMO.enabled`.
   Chỉ giá trị `true` không chứng minh bộ khóa đã được MoMo chấp nhận;
   cần tạo giao dịch sandbox thật để kiểm tra bước tiếp theo.

Dùng chính domain ngrok trả về, không sao chép domain minh họa. IPN gọi server nên
không thể dùng `http://localhost:3000/...`. Web vẫn mở bằng localhost:5173;
không cần đổi frontend sang địa chỉ tunnel chỉ để nhận IPN backend.
