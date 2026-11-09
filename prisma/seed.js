const { PrismaClient } = require("@prisma/client");
const bcrypt = require("bcryptjs");

const prisma = new PrismaClient();

async function main() {
  const passwordHash = await bcrypt.hash("123456", 10);

  const roles = [
    ["ADMIN", "Admin"],
    ["TC", "Teaching Coordinator"],
    ["CM", "Center Management"],
    ["TEACHER", "Teacher"],
    ["CS", "Customer Service"],
    ["SALE", "Sale"],
    ["USER", "Default User"],
  ];

  for (const [roleId, roleName] of roles) {
    await prisma.role.upsert({
      where: { roleId },
      update: { roleName },
      create: { roleId, roleName },
    });
  }

  const userSeeds = [
    ["admin", "admin@iig.local", "ADMIN", null, null],
    ["tc001", "tc001@iig.local", "TC", "emp-tc-001", "TC-001"],
    ["cm001", "cm001@iig.local", "CM", "emp-cm-001", "CM-001"],
    ["teacher001", "teacher001@iig.local", "TEACHER", "teacher-001", "GV-001"],
    ["teacher002", "teacher002@iig.local", "TEACHER", "teacher-002", "GV-002"],
    ["teacher003", "teacher003@iig.local", "TEACHER", "teacher-003", "GV-003"],
    ["cs001", "cs001@iig.local", "CS", "cs-001", "CS-001"],
    ["cs002", "cs002@iig.local", "CS", "cs-002", "CS-002"],
    ["cs003", "cs003@iig.local", "CS", "cs-003", "CS-003"],
    ["sale001", "sale001@iig.local", "SALE", "sale-001", "SALE-001"],
    ["sale002", "sale002@iig.local", "SALE", "sale-002", "SALE-002"],
    ["sale003", "sale003@iig.local", "SALE", "sale-003", "SALE-003"],
  ];

  for (const [username, email, roleId, employeeId, employeeCode] of userSeeds) {
    await prisma.user.upsert({
      where: { username },
      update: { email, roleId, status: "ACTIVE" },
      create: {
        username,
        email,
        passwordHash,
        roleId,
        status: "ACTIVE",
      },
    });

    if (employeeId) {
      const names = {
        teacher001: "David Miller",
        teacher002: "Helena Costa",
        teacher003: "Robert Taylor",
        cs001: "Current CS",
        cs002: "Nguyen Minh Chau",
        cs003: "Tran Quoc Huy",
        tc001: "Teaching Coordinator",
        cm001: "Center Manager",
        sale001: "Nguyen Thu Ha",
        sale002: "Le Minh Khoa",
        sale003: "Pham Gia Han",
      };

      await prisma.employee.upsert({
        where: { employeeId },
        update: {
          username,
          employeeCode,
          fullName: names[username] ?? username,
          status: "ACTIVE",
        },
        create: {
          employeeId,
          username,
          employeeCode,
          fullName: names[username] ?? username,
          status: "ACTIVE",
        },
      });
    }
  }

  const courseSeeds = [
    ["course-ielts", "IELTS"],
    ["course-toeic", "TOEIC"],
    ["course-sat", "SAT"],
    ["course-toefl", "TOEFL iBT"],
  ];

  for (const [courseId, name] of courseSeeds) {
    await prisma.course.upsert({
      where: { courseId },
      update: { name, status: "ACTIVE" },
      create: { courseId, name, status: "ACTIVE" },
    });
  }

  const classSeeds = [
    ["class-0001", "course-ielts", "IELTS-M75-04", "IELTS Mastery 7.5", "2026-10-06", "2027-01-30", "RUNNING", "admin"],
    ["class-0002", "course-toeic", "TOEIC-850-02", "TOEIC Intensive 850+", "2026-10-12", "2027-01-15", "READY", "admin"],
    ["class-0003", "course-toefl", "TOEFL-IBT-01", "TOEFL iBT Complete", "2026-09-15", "2026-12-20", "RUNNING", "cs001"],
    ["class-0004", "course-sat", "SAT-ADV-03", "SAT Math Advanced", "2026-11-01", "2027-02-28", "DRAFT", "admin"],
    ["class-0005", "course-ielts", "IELTS-F65-08", "IELTS Foundation 6.5", "2026-08-01", "2026-11-30", "COMPLETED", "cs001"],
    ["class-0006", "course-ielts", "IELTS-WR-02", "IELTS Writing Intensive", "2026-10-20", "2026-12-22", "READY", "admin"],
    ["class-0007", "course-toeic", "TOEIC-650-01", "TOEIC Foundation 650", "2026-07-01", "2026-09-30", "CLOSED", "admin"],
    ["class-0008", "course-sat", "SAT-FOUND-01", "SAT Foundation", "2026-10-25", "2027-02-10", "DRAFT", "cs001"],
  ];

  for (const [classId, courseId, classCode, name, startDate, endDate, status, createdByUsername] of classSeeds) {
    await prisma.class.upsert({
      where: { classId },
      update: {
        courseId,
        classCode,
        name,
        startDate: new Date(startDate),
        endDate: new Date(endDate),
        status,
        createdByUsername,
      },
      create: {
        classId,
        courseId,
        classCode,
        name,
        startDate: new Date(startDate),
        endDate: new Date(endDate),
        status,
        createdByUsername,
      },
    });
  }

  const classTargetSeeds = [
    ["class-target-001", "class-0001", "OVERALL_BAND", 7.5],
    ["class-target-002", "class-0002", "LR_TOTAL", 850],
    ["class-target-003", "class-0002", "SW_TOTAL", 300],
    ["class-target-004", "class-0003", "OVERALL_1_6", 4.5],
    ["class-target-005", "class-0004", "TOTAL", 1300],
    ["class-target-006", "class-0005", "OVERALL_BAND", 6.5],
    ["class-target-007", "class-0006", "OVERALL_BAND", 7.0],
    ["class-target-008", "class-0007", "LR_TOTAL", 650],
    ["class-target-009", "class-0007", "SW_TOTAL", 200],
    ["class-target-010", "class-0008", "TOTAL", 1100],
  ];

  for (const [classTargetRequirementId, classId, targetType, requiredTarget] of classTargetSeeds) {
    await prisma.classTargetRequirement.upsert({
      where: { classTargetRequirementId },
      update: { classId, targetType, requiredTarget },
      create: { classTargetRequirementId, classId, targetType, requiredTarget },
    });
  }

  const students = [
    ["student-0001", "STU-2024-0891", "Minh Anh Nguyen", "+84 912 345 678", "minhanh.nguyen@email.com", "Active"],
    ["student-0002", "STU-2024-0892", "Duc Thang Tran", "+84 913 276 428", "thang.tran@email.com", "Active"],
    ["student-0003", "STU-2024-0895", "Phuong Linh Vo", "+84 983 112 456", "linh.vo@email.com", "Active"],
    ["student-0004", "STU-2024-0870", "Hoang Nam Le", "+84 912 466 113", "nam.le@email.com", "On Leave"],
    ["student-0005", "STU-2024-0864", "Mai Huong Dang", "+84 934 778 202", "huong.dang@email.com", "Active"],
    ["student-0006", "STU-2024-0752", "Quoc Bao Pham", "+84 906 325 445", "bao.pham@email.com", "Graduated"],
    ["student-0007", "STU-2024-0899", "Thu Ha Nguyen", "+84 928 349 118", "ha.nguyen@email.com", "Active"],
  ];

  for (const [studentId, studentCode, fullName, phone, email, status] of students) {
    await prisma.student.upsert({
      where: { studentId },
      update: { studentCode, fullName, phone, email, status },
      create: { studentId, studentCode, fullName, phone, email, status },
    });
  }

  const studentTargetSeeds = [
    ["st-001-ielts", "student-0001", "course-ielts", "OVERALL_BAND", 7.5],
    ["st-001-toeic-lr", "student-0001", "course-toeic", "LR_TOTAL", 850],
    ["st-001-toeic-sw", "student-0001", "course-toeic", "SW_TOTAL", 320],
    ["st-002-toeic-lr", "student-0002", "course-toeic", "LR_TOTAL", 850],
    ["st-002-toeic-sw", "student-0002", "course-toeic", "SW_TOTAL", 300],
    ["st-002-ielts", "student-0002", "course-ielts", "OVERALL_BAND", 7.5],
    ["st-003-toefl", "student-0003", "course-toefl", "OVERALL_1_6", 4.5],
    ["st-005-ielts", "student-0005", "course-ielts", "OVERALL_BAND", 7.5],
    ["st-007-ielts", "student-0007", "course-ielts", "OVERALL_BAND", 8.0],
  ];

  for (const [studentTargetId, studentId, courseId, targetType, targetValue] of studentTargetSeeds) {
    await prisma.studentTarget.upsert({
      where: { studentTargetId },
      update: { studentId, courseId, targetType, targetValue },
      create: { studentTargetId, studentId, courseId, targetType, targetValue },
    });
  }

  for (const [index, studentId] of ["student-0001","student-0002","student-0005"].entries()) {
    const classStudentId = `class-student-${String(index + 1).padStart(3, "0")}`;
    await prisma.classStudent.upsert({
      where: { classStudentId },
      update: { classId: "class-0001", studentId, status: "ACTIVE", inactiveReason: null },
      create: { classStudentId, classId: "class-0001", studentId, status: "ACTIVE" },
    });
  }

  await prisma.classAccessScope.upsert({
    where: { classAccessScopeId: "class-scope-001" },
    update: { employeeId: "cs-001", classId: "class-0001", status: "ACTIVE", source: "ASSIGNED_SCOPE" },
    create: { classAccessScopeId: "class-scope-001", employeeId: "cs-001", classId: "class-0001", status: "ACTIVE", source: "ASSIGNED_SCOPE" },
  });

  await prisma.teachingSchedule.upsert({
    where: { teachingScheduleId: "teaching-001" },
    update: {},
    create: {
      teachingScheduleId: "teaching-001",
      teacherId: "teacher-001",
      classId: "class-0001",
      date: new Date("2026-10-06"),
      startTime: new Date("1970-01-01T18:30:00Z"),
      endTime: new Date("1970-01-01T20:30:00Z"),
      status: "ASSIGNED",
      assignedById: "emp-tc-001",
    },
  });

  // --- PHẦN THÊM DỮ LIỆU TỰ ĐỘNG ---

  // 1. AppFunction
  for (let i = 1; i <= 20; i++) {
    await prisma.appFunction.upsert({
      where: { functionId: `auto-func-${i}` },
      update: { functionName: `Chức năng mở rộng ${i}` },
      create: { functionId: `auto-func-${i}`, functionName: `Chức năng mở rộng ${i}` },
    });
  }

  // 2. Permission
  for (let i = 1; i <= 20; i++) {
    await prisma.permission.upsert({
      where: {
        roleId_functionId_action: { roleId: "TEACHER", functionId: `auto-func-${i}`, action: "READ" },
      },
      update: {},
      create: { roleId: "TEACHER", functionId: `auto-func-${i}`, action: "READ" },
    });
  }

  // 3. User & Employee & SalaryRank & Certificate
  for (let i = 1; i <= 20; i++) {
    const autoUsername = `auto-user-${i}`;
    const autoEmployeeId = `auto-emp-${i}`;

    await prisma.user.upsert({
      where: { username: autoUsername },
      update: {},
      create: {
        username: autoUsername,
        email: `auto.user.${i}@iig.local`,
        passwordHash,
        roleId: "TEACHER",
        status: "ACTIVE",
      },
    });

    await prisma.employee.upsert({
      where: { employeeId: autoEmployeeId },
      update: {},
      create: {
        employeeId: autoEmployeeId,
        username: autoUsername,
        employeeCode: `AUTO-EMP-${String(i).padStart(3, "0")}`,
        fullName: `Nhân viên tự động ${i}`,
        status: "ACTIVE",
      },
    });

    await prisma.salaryRank.upsert({
      where: { salaryRankId: `auto-rank-${i}` },
      update: {},
      create: {
        salaryRankId: `auto-rank-${i}`,
        roleId: "TEACHER",
        rank: `Bậc lương tự động ${i}`,
        amount: 150000 + i * 5000,
        status: "ACTIVE",
      },
    });

    await prisma.certificate.upsert({
      where: { certificateId: `auto-cert-${i}` },
      update: {},
      create: {
        certificateId: `auto-cert-${i}`,
        employeeId: autoEmployeeId,
        certificateType: `Chứng chỉ tự động ${i}`,
        score: 8.5,
        status: "ACTIVE",
      },
    });
  }

  // Give most demo staff a realistic tenure of 1–3 years.  The salary-review
  // rule remains six months, while the remaining demo employees still model
  // newly hired staff that are not eligible yet.
  const reviewEligibleEmployeeIds = [
    "teacher-001", "teacher-002", "teacher-003",
    "cs-001", "cs-002", "cs-003",
    "sale-001", "sale-002", "sale-003",
    ...Array.from({ length: 11 }, (_, index) => `auto-emp-${index + 1}`),
  ];
  for (const [index, employeeId] of reviewEligibleEmployeeIds.entries()) {
    const yearsAgo = 1 + (index % 3);
    await prisma.employee.update({
      where: { employeeId },
      data: { createdAt: new Date(`${2026 - yearsAgo}-0${(index % 9) + 1}-15T00:00:00.000Z`) },
    });
  }

  // 4. Course & Lesson
  for (let i = 1; i <= 20; i++) {
    const autoCourseId = `auto-course-${i}`;

    await prisma.course.upsert({
      where: { courseId: autoCourseId },
      update: {},
      create: {
        courseId: autoCourseId,
        name: `Khóa học mở rộng ${i}`,
        level: `Cấp độ ${i}`,
        status: "ACTIVE",
      },
    });

    await prisma.lesson.upsert({
      where: { lessonId: `auto-lesson-${i}` },
      update: {},
      create: {
        lessonId: `auto-lesson-${i}`,
        courseId: autoCourseId,
        title: `Bài học tự động số ${i}`,
        orderIndex: i,
        status: "ACTIVE",
      },
    });
  }

  // 5. Class & ClassTargetRequirement
  for (let i = 1; i <= 20; i++) {
    const autoClassId = `auto-class-${i}`;
    const autoCourseId = `auto-course-${i}`;

    await prisma.class.upsert({
      where: { classId: autoClassId },
      update: {},
      create: {
        classId: autoClassId,
        courseId: autoCourseId,
        classCode: `AUTO-CLS-${String(i).padStart(3, "0")}`,
        name: `Lớp học mở rộng ${i}`,
        startDate: new Date("2026-10-01"),
        endDate: new Date("2026-12-31"),
        status: "READY",
      },
    });

    await prisma.classTargetRequirement.upsert({
      where: { classTargetRequirementId: `auto-ctr-${i}` },
      update: {},
      create: {
        classTargetRequirementId: `auto-ctr-${i}`,
        classId: autoClassId,
        targetType: "OVERALL_SCORE",
        requiredTarget: 7.0,
      },
    });
  }

  // 6. Student & StudentTarget & ClassStudent
  for (let i = 1; i <= 20; i++) {
    const autoStudentId = `auto-stu-${i}`;
    const autoClassId = `auto-class-${i}`;
    const autoCourseId = `auto-course-${i}`;

    await prisma.student.upsert({
      where: { studentId: autoStudentId },
      update: {},
      create: {
        studentId: autoStudentId,
        studentCode: `STU-AUTO-${String(i).padStart(3, "0")}`,
        fullName: `Học viên tự động ${i}`,
        status: "Active",
      },
    });

    await prisma.studentTarget.upsert({
      where: { studentTargetId: `auto-st-${i}` },
      update: {},
      create: {
        studentTargetId: `auto-st-${i}`,
        studentId: autoStudentId,
        courseId: autoCourseId,
        targetType: "OVERALL_SCORE",
        targetValue: 7.0,
      },
    });

    await prisma.classStudent.upsert({
      where: { classStudentId: `auto-cs-${i}` },
      update: {},
      create: {
        classStudentId: `auto-cs-${i}`,
        classId: autoClassId,
        studentId: autoStudentId,
        status: "ACTIVE",
      },
    });
  }

  // 7. ClassAccessScope
  for (let i = 1; i <= 20; i++) {
    await prisma.classAccessScope.upsert({
      where: { classAccessScopeId: `auto-cas-${i}` },
      update: {},
      create: {
        classAccessScopeId: `auto-cas-${i}`,
        employeeId: `auto-emp-${i}`,
        classId: `auto-class-${i}`,
        status: "ACTIVE",
        source: "AUTO_ASSIGNED",
      },
    });
  }

  // 8. Availability, TeachingSchedule, StaffSchedule
  for (let i = 1; i <= 20; i++) {
    const autoEmployeeId = `auto-emp-${i}`;
    const autoClassId = `auto-class-${i}`;

    await prisma.availability.upsert({
      where: { availabilityId: `auto-avail-${i}` },
      update: {},
      create: {
        availabilityId: `auto-avail-${i}`,
        employeeId: autoEmployeeId,
        availableDate: new Date("2026-10-20"),
        startTime: new Date("1970-01-01T13:00:00Z"),
        endTime: new Date("1970-01-01T15:00:00Z"),
        status: "approved",
      },
    });

    await prisma.teachingSchedule.upsert({
      where: { teachingScheduleId: `auto-ts-${i}` },
      update: {},
      create: {
        teachingScheduleId: `auto-ts-${i}`,
        teacherId: autoEmployeeId,
        classId: autoClassId,
        date: new Date("2026-10-20"),
        startTime: new Date("1970-01-01T13:00:00Z"),
        endTime: new Date("1970-01-01T15:00:00Z"),
        status: "ASSIGNED",
      },
    });

    await prisma.classSession.upsert({
      // The class-session migration may already have created this slot from
      // teaching_schedules with a legacy ID.  Its natural key is the slot,
      // not our seed ID, so use the composite unique key to keep seeding
      // repeatable.
      where: {
        classId_date_startTime_endTime: {
          classId: autoClassId,
          date: new Date("2026-10-20"),
          startTime: new Date("1970-01-01T13:00:00Z"),
          endTime: new Date("1970-01-01T15:00:00Z"),
        },
      },
      update: { status: "PLANNED" },
      create: {
        classSessionId: `auto-session-${i}`,
        classId: autoClassId,
        date: new Date("2026-10-20"),
        startTime: new Date("1970-01-01T13:00:00Z"),
        endTime: new Date("1970-01-01T15:00:00Z"),
        status: "PLANNED",
      },
    });

    // Do not seed staff shifts for TEACHER users; these shifts cannot be
    // created through the role-validated scheduling API.

  }

  // 9. Assignment, Exam, StudentResult
  for (let i = 1; i <= 20; i++) {
    const autoTeacherId = `auto-emp-${i}`;
    const autoClassId = `auto-class-${i}`;
    const autoStudentId = `auto-stu-${i}`;
    const autoAssignmentId = `auto-assign-${i}`;
    const autoExamId = `auto-exam-${i}`;

    await prisma.assignment.upsert({
      where: { assignmentId: autoAssignmentId },
      update: {},
      create: {
        assignmentId: autoAssignmentId,
        teacherId: autoTeacherId,
        classId: autoClassId,
        title: `Bài tập tự động ${i}`,
        deadline: new Date("2026-11-01T23:59:59Z"),
        status: "OPEN",
      },
    });

    await prisma.exam.upsert({
      where: { examId: autoExamId },
      update: {},
      create: {
        examId: autoExamId,
        teacherId: autoTeacherId,
        classId: autoClassId,
        title: `Bài kiểm tra tự động ${i}`,
        duration: 90,
        examDate: new Date("2026-11-10T08:00:00Z"),
        status: "SCHEDULED",
      },
    });

    await prisma.studentResult.upsert({
      where: { studentResultId: `auto-sr-${i}` },
      update: {},
      create: {
        studentResultId: `auto-sr-${i}`,
        studentId: autoStudentId,
        classId: autoClassId,
        assignmentId: autoAssignmentId,
        score: 8.0,
        evaluatedById: autoTeacherId,
      },
    });
  }

  // 10. EmployeeSalary, EmployeeSalaryDetail, StaffSalary, AuditLog
  for (let i = 1; i <= 20; i++) {
    const autoEmployeeId = `auto-emp-${i}`;
    const autoSalaryId = `auto-es-${i}`;

    await prisma.employeeSalary.upsert({
      where: { employeeSalaryId: autoSalaryId },
      update: {},
      create: {
        employeeSalaryId: autoSalaryId,
        employeeId: autoEmployeeId,
        period: "2026-10",
        totalSessions: 10,
        totalHours: 20.0,
        rate: 200000.0,
        amount: 4000000.0,
        status: "DRAFT",
      },
    });

    await prisma.employeeSalaryDetail.upsert({
      where: { employeeSalaryId: autoSalaryId },
      update: {},
      create: {
        employeeSalaryId: autoSalaryId,
        totalSessions: 10,
        salaryPerHours: 200000.0,
        totalHours: 20.0,
        totalSalary: 4000000.0,
      },
    });

    await prisma.staffSalary.upsert({
      where: { staffSalaryId: `auto-staffsal-${i}` },
      update: {},
      create: {
        staffSalaryId: `auto-staffsal-${i}`,
        employeeId: autoEmployeeId,
        period: "2026-10",
        totalShifts: 22,
        totalHours: 176.0,
        rate: 50000.0,
        amount: 8800000.0,
        status: "DRAFT",
      },
    });

    await prisma.auditLog.upsert({
      where: { auditLogId: `auto-log-${i}` },
      update: {},
      create: {
        auditLogId: `auto-log-${i}`,
        action: "CREATE",
        entityType: "SYSTEM_PROCESS",
        entityId: `SYS-${i}`,
      },
    });
  }

  console.log("Seed completed.");
  console.log("Demo login: admin / 123456");
}

main()
  .catch((error) => {
    console.error(error);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
