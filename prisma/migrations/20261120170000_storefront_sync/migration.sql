ALTER TABLE users ADD COLUMN phone VARCHAR(15), ADD COLUMN contact_email VARCHAR(100);
ALTER TABLE students ADD COLUMN username VARCHAR(50) UNIQUE REFERENCES users(username) ON DELETE RESTRICT;
ALTER TABLE courses
 ADD COLUMN category VARCHAR(40) NOT NULL DEFAULT 'Khác',
 ADD COLUMN price NUMERIC(14,2) CHECK (price >= 0),
 ADD COLUMN image_url VARCHAR(500) NOT NULL DEFAULT '/images/learning.jpg',
 ADD COLUMN target VARCHAR(150), ADD COLUMN entry_description VARCHAR(255),
 ADD COLUMN lesson_count INTEGER NOT NULL DEFAULT 0 CHECK (lesson_count >= 0),
 ADD COLUMN outcomes JSONB NOT NULL DEFAULT '[]', ADD COLUMN syllabus JSONB NOT NULL DEFAULT '[]',
 ADD COLUMN published BOOLEAN NOT NULL DEFAULT true, ADD COLUMN featured BOOLEAN NOT NULL DEFAULT false;
UPDATE courses SET category=CASE WHEN name ILIKE '%IELTS%' THEN 'IELTS' WHEN name ILIKE '%TOEIC%' THEN 'TOEIC' WHEN name ILIKE '%SAT%' THEN 'SAT' WHEN name ILIKE '%TOEFL%' THEN 'TOEFL' ELSE 'Khác' END;
ALTER TABLE classes ADD COLUMN storefront_open BOOLEAN NOT NULL DEFAULT false,
 ADD COLUMN capacity INTEGER NOT NULL DEFAULT 30 CHECK (capacity > 0),
 ADD COLUMN delivery_mode VARCHAR(30) NOT NULL DEFAULT 'Tại trung tâm',
 ADD COLUMN location VARCHAR(255), ADD COLUMN schedule_label VARCHAR(255);
CREATE TABLE storefront_cart_items (
 username VARCHAR(50) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
 course_id VARCHAR(50) NOT NULL REFERENCES courses(course_id) ON DELETE CASCADE,
 class_id VARCHAR(50) NOT NULL REFERENCES classes(class_id) ON DELETE CASCADE,
 PRIMARY KEY(username,course_id)
);
CREATE TABLE storefront_orders (
 order_id VARCHAR(50) PRIMARY KEY, username VARCHAR(50) NOT NULL REFERENCES users(username),
 request_key VARCHAR(80) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','CANCELLED')),
 amount NUMERIC(14,2) NOT NULL CHECK (amount>=0),
 created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(username, request_key)
);
CREATE INDEX storefront_orders_user_date_idx ON storefront_orders(username,created_at);
CREATE TABLE storefront_order_items (
 order_id VARCHAR(50) NOT NULL REFERENCES storefront_orders(order_id),
 class_id VARCHAR(50) NOT NULL REFERENCES classes(class_id),
 course_id VARCHAR(50) NOT NULL REFERENCES courses(course_id),
 course_title VARCHAR(150) NOT NULL, class_code VARCHAR(30) NOT NULL,
 amount NUMERIC(14,2) NOT NULL CHECK (amount>=0), PRIMARY KEY(order_id,class_id)
);
CREATE INDEX storefront_order_items_class_idx ON storefront_order_items(class_id);
CREATE TABLE storefront_consultations (
 consultation_id VARCHAR(50) PRIMARY KEY, full_name VARCHAR(100), phone VARCHAR(15),
 email VARCHAR(100), category VARCHAR(50), message TEXT,
 status VARCHAR(20) NOT NULL DEFAULT 'NEW', created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Preserve the four storefront courses and their initial displayed schedules.
-- Existing management courses/classes remain unchanged.
INSERT INTO courses(course_id,name,description,level,duration,category,price,image_url,target,entry_description,lesson_count,outcomes,syllabus,published,featured) VALUES ('toeic-650','TOEIC 650+','Tăng tốc kỹ năng nghe và đọc với chiến lược làm bài theo từng dạng câu hỏi, kết hợp luyện đề có hướng dẫn.','Trung cấp','10','TOEIC','2490000','/images/toeic.jpg','650+ TOEIC','Trung cấp · Đầu vào 450+','30','["Tối ưu thời gian làm bài","Nâng cao khả năng nghe hội thoại","Thành thạo chiến lược đọc hiểu"]','["Củng cố nền tảng và kiểm tra trình độ","Chiến thuật Listening Part 1–4","Chiến thuật Reading Part 5–7","Thi thử và phân tích kết quả"]','true','true') ON CONFLICT(course_id) DO NOTHING;
INSERT INTO courses(course_id,name,description,level,duration,category,price,image_url,target,entry_description,lesson_count,outcomes,syllabus,published,featured) VALUES ('ielts-starter','IELTS Foundation 4.5–5.5','Tiếp cận đầy đủ bốn kỹ năng nghe, nói, đọc, viết với bài tập thực hành và phản hồi từ giảng viên.','Cơ bản','12','IELTS','4890000','/images/ielts.jpg','4.5 IELTS','Nền tảng · Đầu vào 4.0','36','["Hiểu cấu trúc và yêu cầu bài thi","Phát triển ý tưởng nói và viết","Xây dựng thói quen tự học hiệu quả"]','["Ngữ pháp và từ vựng học thuật cơ bản","Listening & Reading nền tảng","Speaking & Writing từng bước","Thực hành tổng hợp"]','true','true') ON CONFLICT(course_id) DO NOTHING;
INSERT INTO courses(course_id,name,description,level,duration,category,price,image_url,target,entry_description,lesson_count,outcomes,syllabus,published,featured) VALUES ('mos-excel','MOS Excel Associate','Thành thạo bảng tính Excel và ứng dụng vào học tập, công việc với các bài thực hành cụ thể.','Cơ bản','10','MOS','1890000','/images/mos.jpg','MOS Excel Associate','Cơ bản · Không yêu cầu đầu vào','30','["Sử dụng bảng tính tự tin","Vận dụng hàm và xử lý dữ liệu","Sẵn sàng cho kỳ thi MOS"]','["Làm quen bảng tính và dữ liệu","Hàm và công thức thông dụng","Biểu đồ và phân tích dữ liệu","Thực hành và luyện thi MOS"]','true','true') ON CONFLICT(course_id) DO NOTHING;
INSERT INTO courses(course_id,name,description,level,duration,category,price,image_url,target,entry_description,lesson_count,outcomes,syllabus,published,featured) VALUES ('ic3-digital','IC3 Digital Literacy','Xây dựng kỹ năng số thiết yếu, sử dụng máy tính và Internet an toàn, hiệu quả.','Cơ bản','12','IC3','1590000','/images/ic3.jpg','IC3 Digital Literacy','Cơ bản · Kỹ năng số thiết yếu','36','["Nắm vững kiến thức máy tính","Làm việc hiệu quả với ứng dụng số","Sử dụng Internet an toàn"]','["Nền tảng máy tính","Các ứng dụng thiết yếu","Kết nối và cộng tác trực tuyến","An toàn và trách nhiệm số"]','true','true') ON CONFLICT(course_id) DO NOTHING;
INSERT INTO classes(class_id,course_id,class_code,name,start_date,end_date,status,storefront_open,capacity,delivery_mode,location,schedule_label) VALUES ('GK-TOEIC-1226-A','toeic-650','GK-TOEIC-1226-A','TOEIC 650+','2026-12-07','2027-02-14','READY','true','8','Tại trung tâm','Cơ sở GK · TP. Hồ Chí Minh','Thứ 2, 4 · 18:30–20:30') ON CONFLICT(class_id) DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-1','GK-TOEIC-1226-A','2026-12-07','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-2','GK-TOEIC-1226-A','2026-12-09','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-3','GK-TOEIC-1226-A','2026-12-14','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-4','GK-TOEIC-1226-A','2026-12-16','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-5','GK-TOEIC-1226-A','2026-12-21','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-6','GK-TOEIC-1226-A','2026-12-23','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-7','GK-TOEIC-1226-A','2026-12-28','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-8','GK-TOEIC-1226-A','2026-12-30','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-9','GK-TOEIC-1226-A','2027-01-04','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-10','GK-TOEIC-1226-A','2027-01-06','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-11','GK-TOEIC-1226-A','2027-01-11','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-12','GK-TOEIC-1226-A','2027-01-13','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-13','GK-TOEIC-1226-A','2027-01-18','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-14','GK-TOEIC-1226-A','2027-01-20','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-15','GK-TOEIC-1226-A','2027-01-25','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-16','GK-TOEIC-1226-A','2027-01-27','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-17','GK-TOEIC-1226-A','2027-02-01','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-18','GK-TOEIC-1226-A','2027-02-03','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-19','GK-TOEIC-1226-A','2027-02-08','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-A-20','GK-TOEIC-1226-A','2027-02-10','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO classes(class_id,course_id,class_code,name,start_date,end_date,status,storefront_open,capacity,delivery_mode,location,schedule_label) VALUES ('GK-TOEIC-1226-B','toeic-650','GK-TOEIC-1226-B','TOEIC 650+','2026-12-10','2027-02-17','READY','true','12','Trực tuyến','Trực tuyến cùng giảng viên · Zoom','Thứ 3, 5 · 19:00–21:00') ON CONFLICT(class_id) DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-1','GK-TOEIC-1226-B','2026-12-10','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-2','GK-TOEIC-1226-B','2026-12-15','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-3','GK-TOEIC-1226-B','2026-12-17','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-4','GK-TOEIC-1226-B','2026-12-22','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-5','GK-TOEIC-1226-B','2026-12-24','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-6','GK-TOEIC-1226-B','2026-12-29','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-7','GK-TOEIC-1226-B','2026-12-31','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-8','GK-TOEIC-1226-B','2027-01-05','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-9','GK-TOEIC-1226-B','2027-01-07','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-10','GK-TOEIC-1226-B','2027-01-12','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-11','GK-TOEIC-1226-B','2027-01-14','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-12','GK-TOEIC-1226-B','2027-01-19','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-13','GK-TOEIC-1226-B','2027-01-21','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-14','GK-TOEIC-1226-B','2027-01-26','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-15','GK-TOEIC-1226-B','2027-01-28','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-16','GK-TOEIC-1226-B','2027-02-02','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-17','GK-TOEIC-1226-B','2027-02-04','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-18','GK-TOEIC-1226-B','2027-02-09','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-19','GK-TOEIC-1226-B','2027-02-11','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-TOEIC-1226-B-20','GK-TOEIC-1226-B','2027-02-16','19:00','21:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO classes(class_id,course_id,class_code,name,start_date,end_date,status,storefront_open,capacity,delivery_mode,location,schedule_label) VALUES ('GK-TOEIC-1226-C','toeic-650','GK-TOEIC-1226-C','TOEIC 650+','2026-12-14','2027-02-21','READY','false','30','Tại trung tâm','Cơ sở GK · TP. Hồ Chí Minh','Thứ 2, 4 · 18:30–20:30') ON CONFLICT(class_id) DO NOTHING;
INSERT INTO classes(class_id,course_id,class_code,name,start_date,end_date,status,storefront_open,capacity,delivery_mode,location,schedule_label) VALUES ('GK-IELTS-1226','ielts-starter','GK-IELTS-1226','IELTS Foundation 4.5–5.5','2026-12-12','2027-03-05','READY','true','6','Tại trung tâm','Cơ sở GK · TP. Hồ Chí Minh','Thứ 7, CN · 08:30–11:00') ON CONFLICT(class_id) DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-1','GK-IELTS-1226','2026-12-12','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-2','GK-IELTS-1226','2026-12-13','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-3','GK-IELTS-1226','2026-12-19','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-4','GK-IELTS-1226','2026-12-20','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-5','GK-IELTS-1226','2026-12-26','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-6','GK-IELTS-1226','2026-12-27','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-7','GK-IELTS-1226','2027-01-02','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-8','GK-IELTS-1226','2027-01-03','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-9','GK-IELTS-1226','2027-01-09','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-10','GK-IELTS-1226','2027-01-10','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-11','GK-IELTS-1226','2027-01-16','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-12','GK-IELTS-1226','2027-01-17','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-13','GK-IELTS-1226','2027-01-23','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-14','GK-IELTS-1226','2027-01-24','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-15','GK-IELTS-1226','2027-01-30','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-16','GK-IELTS-1226','2027-01-31','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-17','GK-IELTS-1226','2027-02-06','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-18','GK-IELTS-1226','2027-02-07','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-19','GK-IELTS-1226','2027-02-13','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-20','GK-IELTS-1226','2027-02-14','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-21','GK-IELTS-1226','2027-02-20','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-22','GK-IELTS-1226','2027-02-21','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-23','GK-IELTS-1226','2027-02-27','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IELTS-1226-24','GK-IELTS-1226','2027-02-28','08:30','11:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO classes(class_id,course_id,class_code,name,start_date,end_date,status,storefront_open,capacity,delivery_mode,location,schedule_label) VALUES ('GK-MOS-1226','mos-excel','GK-MOS-1226','MOS Excel Associate','2026-12-15','2027-02-22','READY','true','10','Tại trung tâm','Cơ sở GK · TP. Hồ Chí Minh','Thứ 3, 5 · 18:30–20:30') ON CONFLICT(class_id) DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-1','GK-MOS-1226','2026-12-15','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-2','GK-MOS-1226','2026-12-17','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-3','GK-MOS-1226','2026-12-22','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-4','GK-MOS-1226','2026-12-24','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-5','GK-MOS-1226','2026-12-29','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-6','GK-MOS-1226','2026-12-31','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-7','GK-MOS-1226','2027-01-05','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-8','GK-MOS-1226','2027-01-07','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-9','GK-MOS-1226','2027-01-12','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-10','GK-MOS-1226','2027-01-14','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-11','GK-MOS-1226','2027-01-19','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-12','GK-MOS-1226','2027-01-21','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-13','GK-MOS-1226','2027-01-26','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-14','GK-MOS-1226','2027-01-28','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-15','GK-MOS-1226','2027-02-02','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-16','GK-MOS-1226','2027-02-04','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-17','GK-MOS-1226','2027-02-09','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-18','GK-MOS-1226','2027-02-11','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-19','GK-MOS-1226','2027-02-16','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-MOS-1226-20','GK-MOS-1226','2027-02-18','18:30','20:30','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO classes(class_id,course_id,class_code,name,start_date,end_date,status,storefront_open,capacity,delivery_mode,location,schedule_label) VALUES ('GK-IC3-1226','ic3-digital','GK-IC3-1226','IC3 Digital Literacy','2026-12-19','2027-03-12','READY','true','16','Tại trung tâm','Cơ sở GK · TP. Hồ Chí Minh','Thứ 7, CN · 13:00–15:00') ON CONFLICT(class_id) DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-1','GK-IC3-1226','2026-12-19','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-2','GK-IC3-1226','2026-12-20','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-3','GK-IC3-1226','2026-12-26','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-4','GK-IC3-1226','2026-12-27','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-5','GK-IC3-1226','2027-01-02','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-6','GK-IC3-1226','2027-01-03','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-7','GK-IC3-1226','2027-01-09','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-8','GK-IC3-1226','2027-01-10','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-9','GK-IC3-1226','2027-01-16','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-10','GK-IC3-1226','2027-01-17','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-11','GK-IC3-1226','2027-01-23','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-12','GK-IC3-1226','2027-01-24','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-13','GK-IC3-1226','2027-01-30','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-14','GK-IC3-1226','2027-01-31','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-15','GK-IC3-1226','2027-02-06','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-16','GK-IC3-1226','2027-02-07','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-17','GK-IC3-1226','2027-02-13','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-18','GK-IC3-1226','2027-02-14','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-19','GK-IC3-1226','2027-02-20','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-20','GK-IC3-1226','2027-02-21','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-21','GK-IC3-1226','2027-02-27','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-22','GK-IC3-1226','2027-02-28','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-23','GK-IC3-1226','2027-03-06','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status) VALUES ('GK-IC3-1226-24','GK-IC3-1226','2027-03-07','13:00','15:00','PLANNED') ON CONFLICT DO NOTHING;
