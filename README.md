# Backend IIG — Spring Boot + PostgreSQL + JWT

## Thử kiến trúc Spring Microservices (nhánh migration)

Nhánh `feature/spring-microservices-hr-certificates-20261109` (nối tiếp Dashboard, Reporting, Identity và Catalog) có Docker stack **riêng** để thử kiến trúc mới.
Bản `docker-compose.yml` bên dưới vẫn chạy monolith như trước.

```bash
# Thay các giá trị mẫu trong microservices.env.example bằng bí mật thật trong .env
docker compose -f docker-compose.microservices.yml up --build -d
# API qua Spring Cloud Gateway: http://localhost:8080/api/**
# Audit, Catalog, Identity, Reporting, HR, Payroll và Scheduling có DB riêng (8 PostgreSQL tổng cộng)
```

Tài liệu triển khai, kiến trúc và giới hạn giai đoạn đầu:
[docs/architecture/microservices-migration.md](docs/architecture/microservices-migration.md).

**Lưu ý:** Đây là giai đoạn 8e: Scheduling sở hữu lịch rảnh, bản đọc độc lập của lịch phân công và các reservation tạm thời theo nhân sự/ngày/giờ để chặn những giao dịch phân công đang chạy bị trùng lịch. Các API GET `/api/teaching-schedules`, `/api/staff-schedules`, `/api/schedules/me` đọc từ Scheduling PostgreSQL. Core vẫn là nơi DUY NHẤT tạo/sửa/hủy phân công và ghi điểm danh để giữ giao dịch lớp học và bảo vệ ca CS cuối cùng. Reservation chỉ chặn các thao tác đang thực hiện đồng thời, không thay thế bước kiểm tra lịch đã ghi thành công trong Core. Core gửi thay đổi qua transactional outbox. Khi FE đọc lịch qua Gateway, Scheduling kiểm tra và áp dụng các thay đổi đã commit còn chờ trước khi trả danh sách; nếu không thể đồng bộ thì trả 503 thay vì âm thầm trả dữ liệu cũ. Đây là read-after-write barrier theo từng request, chưa phải giao dịch phân tán tuyến tính hóa khi có ghi đồng thời. Chưa hoàn thiện 100% Microservices; không dùng bí mật demo cho production.
Đối với lệnh phân công Teacher/CS/Sale theo một ca, theo lô, toàn khóa,
sửa hoặc hủy phân công, client có thể truyền header
`Idempotency-Key: <mã-yêu-cầu-duy-nhất>`. Nếu mất kết nối sau khi ghi thành công,
gửi lại **cùng key, cùng endpoint và cùng payload** để lấy lại kết quả thay vì
tạo bản ghi hoặc audit lần hai. Dùng key mới khi chủ đích thực hiện thao tác
mới; dùng lại key với payload khác sẽ trả `409 Conflict`. Không truyền header
thì hành vi API cũ giữ nguyên. Hiện receipt cùng transaction vẫn thuộc Core;
đây chưa phải là chuyển quyền ghi phân công sang Scheduling.

**Đối soát trước cutover:** Scheduling bổ sung API nội bộ
`GET /internal/scheduling/assignments/verify` (bắt buộc header
`X-Internal-Key`, chỉ gọi qua mạng Docker). Kết quả `ready=true` chỉ khi
các bản ghi Core/Scheduling khớp phiên bản, nội dung lịch, lịch sử hủy/xóa,
không còn outbox chờ đồng bộ và không có lịch Core thiếu theo dõi. Sai lệch
trả `409`, nguồn lỗi trả `503`. API này không mở qua Gateway và **chưa
chuyển quyền ghi phân công sang Scheduling**. Xem tài liệu kiến trúc để biết
giới hạn kiểm tra và kế hoạch chuyển quyền ghi thực sự.

**Chuẩn bị chuyển quyền ghi (chưa bật):** Core đã có khóa ghi mức
PostgreSQL `scheduling_assignment_writer_control` để chặn ghi trong
trường hợp kiểm tra cutover. Scheduling đã có bản `write_shadow` riêng
với phiên bản/tombstone và `command_inbox` chống lặp lệnh, nhưng lệnh
trong inbox mới ở trạng thái `STAGED`; không thể tự tạo/sửa/hủy lịch.
Core cũng bổ sung dữ liệu phiên bản cho lớp và buổi học. Hiện Academic,
điểm danh và Payroll chưa hoàn tất giao dịch phối hợp giữa các service,
nên **Core vẫn là người ghi phân công duy nhất**.

Chỉ khi có cửa sổ bảo trì chủ động, chạy thử bước đối soát và tự phục hồi
Core bằng `IIG_ALLOW_CUTOVER_CHECK=1 bash scripts/check-scheduling-cutover.sh`.
Không dùng trong thời điểm người dùng đang phân công, vì script tạm khóa
bảng lịch. Kết quả `READY` chỉ là kiểm tra dữ liệu, **không tự bật cutover**.

**Academic Session Lease (mốc 8b-3c):** Core bổ sung API nội bộ
cấp/gia hạn/giải phóng lease theo phiên bản lớp và buổi học. Khi lease có
hiệu lực, PostgreSQL ngăn thay đổi buổi học và ngăn bật lại Core assignment
writes. Chỉ cấp lease nếu Core đang `QUIESCED`; nhánh chạy bình thường
vẫn là `CORE`. Client có `commandKey` cố định để retry lấy lại đúng
lease khi request bị gián đoạn. **Hiện chưa có saga thực thi lệnh phân
công liên service và chưa chuyển quyền ghi từ Core sang Scheduling**.

**Outbox chuyển quyền ghi (Phase 8d-1):** Scheduling có outbox
sự kiện phân công, lệnh `PENDING_REFERENCE` và bộ chuyển dữ liệu có
retry/ack sang Core. Core cũng có API nội bộ nhận bản tham chiếu có
kiểm tra phiên bản, hash và receipt idempotent, **hiện chỉ hỗ trợ ca
CS/Sale không gắn lớp học**. Cả hai đường truyền vẫn **tắt mặc định**
(`SCHEDULING_REFERENCE_WRITES_ENABLED=false` và
`SCHEDULING_REFERENCE_RELAY_ENABLED=false`). Chưa có executor ghi
phân công độc lập, chưa chuyển route POST/PUT/DELETE sang Scheduling.
Không bật các cờ này nếu chưa làm cutover/compensation và kiểm thử đầy đủ.

**Ghi phân công độc lập trong Scheduling (Phase 8d-2):** Đã có
executor nội bộ để **tạo/chỉnh sửa/hủy ca CS hoặc Sale không gắn lớp học** từ command
inbox đã STAGED. Khi Core đang QUIESCED, lệnh kiểm tra quyền CM hiện hành,
lease lịch rảnh đã duyệt, lịch nhân sự đang trùng, rồi ghi bản đọc, dữ liệu
chuẩn hóa, outbox và trạng thái PENDING_REFERENCE trong một transaction
Scheduling. Event được relay sang Core, chỉ xác nhận COMMITTED sau khi Core
lưu bản tham chiếu thành công. Khi Core chưa xác nhận hoặc cần đối soát,
API đọc lịch trả 503 thay vì hiển thị một lịch chưa đồng bộ như đã thành công.

Các thao tác mới vẫn **chỉ có API nội bộ**, không mở qua Gateway; chưa hỗ
trợ Teacher, CS hỗ trợ lớp, bulk staffing, chỉnh sửa ca, attendance và
Payroll. Cờ `SCHEDULING_ASSIGNMENT_EXECUTOR_ENABLED=false` vẫn là mặc
định, cùng với hai cờ relay/reference đã tắt. **Không tự bật ba cờ này
trong hệ thống đang sử dụng**, và không chạy script dry-run cutover nếu
đã bật executor: script sẽ từ chối để tránh mở lại Core gây hai nơi ghi.

**Lưu ý chỉnh sửa ca và hoàn nguyên:** Chỉ được sửa những ca độc lập
do Scheduling tạo, đã được Core xác nhận; không đổi nhân viên/role/lớp.
Khi sửa ngày/giờ, hệ thống đòi reservation mới còn hiệu lực và ghi
event `EDIT_STAFF` có phiên bản tăng dần. Core chỉ áp dụng chỉnh sửa
nếu ca vẫn đang hoạt động và không gắn lớp. Một event cũ không thể tự
được xác nhận nếu Core không còn bằng chứng nó từng được commit.
Kể từ khi Core nhận được sự kiện phân công đầu tiên từ Scheduling,
PostgreSQL sẽ chặn đổi đơn giản `QUIESCED -> CORE`; hoàn nguyên lúc đó
phải qua quy trình đối soát/rollback được kiểm duyệt, không reset cờ
tùy ý. Mọi API này vẫn riêng tư, mặc định **không bật** trên Compose.

**Khôi phục event chuyển giao bị treo (Phase 8d-4):** Scheduling có
thêm API nội bộ xác minh event đã được Core lưu thật khi relay thất bại
nhiều lần. Chỉ quản trị viên ADMIN còn hiệu lực và có service key mới
gọi được; API chỉ xác nhận khi bản ghi receipt của Core khớp chính xác
`eventId`, phiên bản và SHA-256 hash. Nếu Core không chứng minh được,
lệnh vẫn ở trạng thái cần đối soát, không tự rollback và không bị đánh dấu
thành công giả. Cờ `SCHEDULING_RECOVERY_ENABLED=false` được giữ tắt
trong cấu hình mặc định. Việc khôi phục này **không** mở thêm route ghi
FE/Gateway và không thay thế kiểm thử saga xuyên service.

**Bảo vệ chuyển quyền ghi (Phase 8d-5):** Scheduling phải lấy
`writer claim` ở Core trước khi commit mọi lệnh tạo/sửa/hủy ca CS/Sale
độc lập. Core kiểm tra CM còn hiệu lực, mode `QUIESCED`, và lưu claim
có định danh lệnh ổn định. Core không thể bật lại writer cũ khi vẫn còn
claim, kể cả khi event chưa đến Core. Claim được đánh dấu `CONFIRMED`
trong cùng transaction với bản ghi tham chiếu và receipt.

Claim không tự hết hạn. Nếu request bị đứt sau khi Core đã lưu claim
nhưng trước khi Scheduling commit, cần đối soát claim, command inbox
và outbox ở cả hai database trước khi hoàn nguyên; **không tự xóa claim
hoặc reset `writer_mode`**. Migration Academic Lease ở mốc 8b-3c cũng đã
được sửa cú pháp PL/pgSQL để database mới có thể apply; với môi trường
đã từng apply migration lỗi, phải kiểm tra lịch sử migration trước khi
chạy lại. Các cờ cutover vẫn tắt mặc định.

**Lịch sử event phân công (Phase 8e):** Core nay lưu receipt
bất biến **theo từng event**, không chỉ receipt phiên bản mới nhất.
Việc retry sự kiện cũ chỉ thành công nếu khớp chính xác event ID,
phiên bản, hash và danh tính lệnh đã áp dụng. ADMIN có thể đối soát
event treo bằng API nội bộ đọc receipt theo event ID; việc đối soát
không cho phép đánh dấu hoàn tất thủ công khi Core thiếu bằng chứng.

Trigger PostgreSQL ngăn sửa/xóa nội dung domain outbox ở Scheduling
và lịch sử receipt ở Core. Migration có backfill receipt cuối cùng
từ triển khai cũ, nhưng không tự tạo lịch sử cho các phiên bản không
còn dữ liệu. **Chưa bật public write cutover**: ca giáo viên,
CS hỗ trợ lớp, điểm danh và Payroll vẫn cần chuyển giao cùng
các kiểm thử tích hợp.

**Academic read microservice (bước đầu):** Có thêm service riêng cổng nội
bộ `8099` và PostgreSQL `iig_academic`, sở hữu bảng snapshot của
`GET /api/class-planning` và `GET /api/classes/{classId}/planned-sessions`.
Gateway chuyển hai API GET này qua Academic; mọi POST/PUT/PATCH và nghiệp vụ
lớp/điểm danh vẫn đi Core. Service kiểm tra JWT còn hiệu lực và gọi nguồn
Core trong từng lần đọc, lưu bản sao theo người dùng+role+loại dữ liệu,
trả JSON hợp đồng cũ cùng `Cache-Control: no-store`; **không dùng cache cũ
khi Core lỗi**. Đây là bước chuyển read model, chưa phải quyền ghi Academic
độc lập hay khả năng tắt Core. Docker smoke so sánh JSON Academic và Core,
kiểm tra quyền và chứng minh Academic sở hữu PostgreSQL riêng.

**Attendance read microservice (bước đọc an toàn):** Service riêng trên mạng
nội bộ cổng `8100` với PostgreSQL `iig_attendance` sở hữu snapshot
điểm danh giảng viên theo CS/lớp. Gateway chuyển
`GET /api/classes/{classId}/teacher-attendance` sang Attendance, nhưng
`PUT /api/classes/{classId}/teacher-attendance/{teachingScheduleId}`
tiếp tục do Core xử lý. Mỗi GET xác thực JWT hiện hành và được Core xác
nhận quyền CS quản lý/hỗ trợ chính lớp đó; JSON giữ nguyên cho FE, không
trả bản cache cũ khi Core lỗi. Phần ghi điểm danh và dữ liệu lương **chưa
được chuyển sang Attendance**. Docker smoke kiểm tra quyền CS/ADMIN,
đối chiếu phản hồi với Core và persistence ở DB mới.

**Attendance Event Relay (Phase 8f-1):** Core đã có outbox giao dịch
`teacher_attendance_source_outbox` ghi sự kiện mỗi lần điểm danh giáo viên
được tạo, sửa hoặc xóa. Attendance service đọc feed qua API nội bộ
`/internal/attendance/events`, xác minh SHA-256 rồi ghi bản ghi nguồn
`attendance_teacher_source_records` cùng receipt trong PostgreSQL
`iig_attendance`. Chỉ sau commit, Attendance mới gửi xác nhận
`/internal/attendance/events/ack` cho Core. Nếu mất kết nối, event
vẫn nằm trong Core và được gửi lại; dữ liệu/phiên bản cũ không được
ghi đè bản mới. Smoke kiểm tra insert/update/delete, tombstone và
receipt chính xác ở hai database. Feed bị ẩn khỏi Gateway.

**Phạm vi:** Quyền `PUT` điểm danh vẫn ở Core, vẫn giữ kiểm tra CS
đang hỗ trợ đúng buổi, thời điểm buổi học và ràng buộc Payroll.
Đây là đồng bộ nguồn dữ liệu để chuẩn bị chuyển quyền ghi về Attendance,
chưa phải cutover nghiệp vụ điểm danh.

Dừng stack bằng `docker compose -f docker-compose.microservices.yml down` để giữ dữ liệu.


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
VITE_API_URL=http://localhost:8080/api
```
CORS đã được cấu hình sẵn cho phép `http://localhost:5173` (Vite dev server) trong `SecurityConfig.java`.
