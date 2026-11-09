# Backend IIG — Spring Boot + PostgreSQL + JWT

## Thử kiến trúc Spring Microservices (nhánh migration)

Nhánh `feature/spring-microservices-migration-20261109` có Docker stack **riêng** để thử kiến trúc mới.
Bản `docker-compose.yml` bên dưới vẫn chạy monolith như trước.

```bash
# Thay các giá trị mẫu trong microservices.env.example bằng bí mật thật trong .env
docker compose -f docker-compose.microservices.yml up --build -d
# API qua Spring Cloud Gateway: http://localhost:8080/api/**
# Audit và Course Catalog có PostgreSQL riêng (3 database tổng cộng), không mở cổng công khai
```

Tài liệu triển khai, kiến trúc và giới hạn giai đoạn đầu:
[docs/architecture/microservices-migration.md](docs/architecture/microservices-migration.md).

**Lưu ý:** Đây là giai đoạn 2 (Gateway + Audit + Catalog). Class, Student, Scheduling, HR và Auth chưa được tách thành
những microservice độc lập; không dùng cấu hình demo làm production.
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
