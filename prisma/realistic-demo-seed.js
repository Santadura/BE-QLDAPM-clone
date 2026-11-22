const campuses = [
  { id: "center-01", name: "Cơ sở chính" },
  { id: "center-02", name: "Cơ sở Quận 1" },
  { id: "center-03", name: "Cơ sở Quận 3" },
];

const time = (value) => new Date(`1970-01-01T${value}:00Z`);
const date = (value) => new Date(`${value}T00:00:00Z`);

async function cleanupLegacyAutoSeed(prisma) {
  console.log("Removing legacy auto-* demo rows...");

  await prisma.teacherSessionAttendance.deleteMany({
    where: { teachingScheduleId: { startsWith: "auto-ts-" } },
  });
  await prisma.studentResult.deleteMany({
    where: { studentResultId: { startsWith: "auto-sr-" } },
  });
  await prisma.assignment.deleteMany({
    where: { assignmentId: { startsWith: "auto-assign-" } },
  });
  await prisma.exam.deleteMany({
    where: { examId: { startsWith: "auto-exam-" } },
  });
  await prisma.salaryCalculationItem.deleteMany({
    where: {
      OR: [
        { payrollId: { startsWith: "auto-es-" } },
        { payrollId: { startsWith: "auto-staffsal-" } },
      ],
    },
  });
  await prisma.employeeSalaryDetail.deleteMany({
    where: { employeeSalaryId: { startsWith: "auto-es-" } },
  });
  await prisma.employeeSalary.deleteMany({
    where: { employeeSalaryId: { startsWith: "auto-es-" } },
  });
  await prisma.staffSalary.deleteMany({
    where: { staffSalaryId: { startsWith: "auto-staffsal-" } },
  });
  await prisma.salaryReviewRequest.deleteMany({
    where: { employeeId: { startsWith: "auto-emp-" } },
  });
  await prisma.salaryRankHistory.deleteMany({
    where: { employeeId: { startsWith: "auto-emp-" } },
  });
  await prisma.classSession.deleteMany({
    where: {
      OR: [
        { classSessionId: { startsWith: "auto-session-" } },
        { classId: { startsWith: "auto-class-" } },
      ],
    },
  });
  await prisma.teachingSchedule.deleteMany({
    where: { teachingScheduleId: { startsWith: "auto-ts-" } },
  });
  await prisma.staffSchedule.deleteMany({
    where: { staffScheduleId: { startsWith: "auto-" } },
  });
  await prisma.availability.deleteMany({
    where: { availabilityId: { startsWith: "auto-avail-" } },
  });
  await prisma.classAccessScope.deleteMany({
    where: { classAccessScopeId: { startsWith: "auto-cas-" } },
  });
  await prisma.classStudent.deleteMany({
    where: { classStudentId: { startsWith: "auto-cs-" } },
  });
  await prisma.studentTarget.deleteMany({
    where: { studentTargetId: { startsWith: "auto-st-" } },
  });
  await prisma.classTargetRequirement.deleteMany({
    where: { classTargetRequirementId: { startsWith: "auto-ctr-" } },
  });
  await prisma.lesson.deleteMany({
    where: { lessonId: { startsWith: "auto-lesson-" } },
  });
  await prisma.certificate.deleteMany({
    where: { certificateId: { startsWith: "auto-cert-" } },
  });
  await prisma.auditLog.deleteMany({
    where: {
      OR: [
        { auditLogId: { startsWith: "auto-log-" } },
        { entityId: { startsWith: "auto-" } },
      ],
    },
  });
  await prisma.class.deleteMany({
    where: { classId: { startsWith: "auto-class-" } },
  });
  await prisma.student.deleteMany({
    where: { studentId: { startsWith: "auto-stu-" } },
  });
  await prisma.course.deleteMany({
    where: { courseId: { startsWith: "auto-course-" } },
  });
  await prisma.employee.deleteMany({
    where: { employeeId: { startsWith: "auto-emp-" } },
  });
  await prisma.user.deleteMany({
    where: { username: { startsWith: "auto-user-" } },
  });
  await prisma.salaryRank.deleteMany({
    where: { salaryRankId: { startsWith: "auto-rank-" } },
  });
  await prisma.permission.deleteMany({
    where: { functionId: { startsWith: "auto-func-" } },
  });
  await prisma.appFunction.deleteMany({
    where: { functionId: { startsWith: "auto-func-" } },
  });
}

async function seedFunctionsAndPermissions(prisma) {
  const functions = [
    ["FUNC_STUDENTS", "Quản lý học viên"],
    ["FUNC_CLASSES", "Quản lý lớp học"],
    ["FUNC_AVAILABILITY", "Đăng ký lịch rảnh"],
    ["FUNC_TEACHING_ASSIGN", "Phân công giáo viên"],
    ["FUNC_STAFF_ASSIGN", "Phân công CS và Sale"],
    ["FUNC_ATTENDANCE", "Điểm danh"],
    ["FUNC_PAYROLL", "Quản lý công lương"],
    ["FUNC_STATISTICS", "Thống kê"],
    ["FUNC_ACCOUNTS", "Quản lý tài khoản"],
  ];

  for (const [functionId, functionName] of functions) {
    await prisma.appFunction.upsert({
      where: { functionId },
      update: { functionName },
      create: { functionId, functionName },
    });
  }

  const permissionMap = {
    ADMIN: [
      ["FUNC_STUDENTS", "MANAGE"],
      ["FUNC_CLASSES", "MANAGE"],
      ["FUNC_PAYROLL", "MANAGE"],
      ["FUNC_STATISTICS", "READ"],
      ["FUNC_ACCOUNTS", "MANAGE"],
    ],
    TC: [
      ["FUNC_AVAILABILITY", "REVIEW"],
      ["FUNC_TEACHING_ASSIGN", "MANAGE"],
      ["FUNC_PAYROLL", "MANAGE_TEACHER"],
    ],
    CM: [
      ["FUNC_AVAILABILITY", "REVIEW"],
      ["FUNC_STAFF_ASSIGN", "MANAGE"],
      ["FUNC_PAYROLL", "MANAGE_STAFF"],
    ],
    TEACHER: [
      ["FUNC_AVAILABILITY", "WRITE"],
      ["FUNC_CLASSES", "READ_ASSIGNED"],
      ["FUNC_ATTENDANCE", "READ"],
    ],
    CS: [
      ["FUNC_AVAILABILITY", "WRITE"],
      ["FUNC_CLASSES", "MANAGE_ASSIGNED"],
      ["FUNC_ATTENDANCE", "WRITE"],
    ],
    SALE: [
      ["FUNC_AVAILABILITY", "WRITE"],
      ["FUNC_STUDENTS", "CREATE_PROFILE"],
    ],
  };

  for (const [roleId, permissions] of Object.entries(permissionMap)) {
    for (const [functionId, action] of permissions) {
      await prisma.permission.upsert({
        where: {
          roleId_functionId_action: { roleId, functionId, action },
        },
        update: {},
        create: { roleId, functionId, action },
      });
    }
  }
}

async function seedSalaryRanks(prisma) {
  const ranks = [
    ["rank-teacher-junior", "TEACHER", "Giáo viên Junior", 180000],
    ["rank-teacher-standard", "TEACHER", "Giáo viên Standard", 230000],
    ["rank-teacher-senior", "TEACHER", "Giáo viên Senior", 300000],
    ["rank-cs-standard", "CS", "CS Standard", 55000],
    ["rank-cs-senior", "CS", "CS Senior", 70000],
    ["rank-sale-standard", "SALE", "Sale Standard", 55000],
    ["rank-sale-senior", "SALE", "Sale Senior", 70000],
    ["rank-tc", "TC", "Teaching Coordinator", 90000],
    ["rank-cm", "CM", "Center Manager", 95000],
  ];

  for (const [salaryRankId, roleId, rank, amount] of ranks) {
    await prisma.salaryRank.upsert({
      where: { salaryRankId },
      update: { roleId, rank, amount, status: "ACTIVE" },
      create: { salaryRankId, roleId, rank, amount, status: "ACTIVE" },
    });
  }
}

async function seedEmployees(prisma, passwordHash) {
  const people = [
    {
      username: "teacher004",
      email: "teacher004@gk-demo.local",
      roleId: "TEACHER",
      employeeId: "demo-teacher-004",
      employeeCode: "GV-004",
      fullName: "Nguyễn Hoàng Anh",
      phone: "0908123404",
      specialization: "IELTS Speaking & Writing",
      salaryRankId: "rank-teacher-standard",
      createdAt: "2024-03-18",
    },
    {
      username: "teacher005",
      email: "teacher005@gk-demo.local",
      roleId: "TEACHER",
      employeeId: "demo-teacher-005",
      employeeCode: "GV-005",
      fullName: "Trần Thảo Vy",
      phone: "0908123405",
      specialization: "TOEIC Listening & Reading",
      salaryRankId: "rank-teacher-standard",
      createdAt: "2023-08-10",
    },
    {
      username: "teacher006",
      email: "teacher006@gk-demo.local",
      roleId: "TEACHER",
      employeeId: "demo-teacher-006",
      employeeCode: "GV-006",
      fullName: "Lê Đức Minh",
      phone: "0908123406",
      specialization: "IELTS Academic",
      salaryRankId: "rank-teacher-senior",
      createdAt: "2022-11-02",
    },
    {
      username: "teacher007",
      email: "teacher007@gk-demo.local",
      roleId: "TEACHER",
      employeeId: "demo-teacher-007",
      employeeCode: "GV-007",
      fullName: "Phạm Khánh Linh",
      phone: "0908123407",
      specialization: "SAT Math",
      salaryRankId: "rank-teacher-standard",
      createdAt: "2024-06-25",
    },
    {
      username: "teacher008",
      email: "teacher008@gk-demo.local",
      roleId: "TEACHER",
      employeeId: "demo-teacher-008",
      employeeCode: "GV-008",
      fullName: "Võ Gia Huy",
      phone: "0908123408",
      specialization: "TOEIC Communication",
      salaryRankId: "rank-teacher-junior",
      createdAt: "2025-04-15",
    },
    {
      username: "teacher009",
      email: "teacher009@gk-demo.local",
      roleId: "TEACHER",
      employeeId: "demo-teacher-009",
      employeeCode: "GV-009",
      fullName: "Bùi Ngọc Mai",
      phone: "0908123409",
      specialization: "IELTS Foundation",
      salaryRankId: "rank-teacher-standard",
      createdAt: "2023-12-01",
    },
    {
      username: "cs004",
      email: "cs004@gk-demo.local",
      roleId: "CS",
      employeeId: "demo-cs-004",
      employeeCode: "CS-004",
      fullName: "Nguyễn Mỹ Duyên",
      phone: "0908234504",
      specialization: "Chăm sóc lớp học",
      salaryRankId: "rank-cs-standard",
      createdAt: "2024-02-12",
    },
    {
      username: "cs005",
      email: "cs005@gk-demo.local",
      roleId: "CS",
      employeeId: "demo-cs-005",
      employeeCode: "CS-005",
      fullName: "Trương Quốc Bảo",
      phone: "0908234505",
      specialization: "Vận hành lớp",
      salaryRankId: "rank-cs-senior",
      createdAt: "2023-05-08",
    },
    {
      username: "cs006",
      email: "cs006@gk-demo.local",
      roleId: "CS",
      employeeId: "demo-cs-006",
      employeeCode: "CS-006",
      fullName: "Lâm Thanh Tâm",
      phone: "0908234506",
      specialization: "Chăm sóc học viên",
      salaryRankId: "rank-cs-standard",
      createdAt: "2024-09-20",
    },
    {
      username: "sale004",
      email: "sale004@gk-demo.local",
      roleId: "SALE",
      employeeId: "demo-sale-004",
      employeeCode: "SALE-004",
      fullName: "Đặng Minh Thư",
      phone: "0908345604",
      specialization: "Tư vấn IELTS",
      salaryRankId: "rank-sale-standard",
      createdAt: "2024-01-05",
    },
    {
      username: "sale005",
      email: "sale005@gk-demo.local",
      roleId: "SALE",
      employeeId: "demo-sale-005",
      employeeCode: "SALE-005",
      fullName: "Phan Tuấn Kiệt",
      phone: "0908345605",
      specialization: "Tư vấn TOEIC",
      salaryRankId: "rank-sale-senior",
      createdAt: "2023-03-14",
    },
    {
      username: "sale006",
      email: "sale006@gk-demo.local",
      roleId: "SALE",
      employeeId: "demo-sale-006",
      employeeCode: "SALE-006",
      fullName: "Nguyễn Hồng Nhung",
      phone: "0908345606",
      specialization: "Tư vấn SAT & TOEFL",
      salaryRankId: "rank-sale-standard",
      createdAt: "2024-07-01",
    },
  ];

  for (const person of people) {
    await prisma.user.upsert({
      where: { username: person.username },
      update: {
        email: person.email,
        roleId: person.roleId,
        status: "ACTIVE",
      },
      create: {
        username: person.username,
        email: person.email,
        passwordHash,
        roleId: person.roleId,
        status: "ACTIVE",
        createdAt: date(person.createdAt),
      },
    });

    await prisma.employee.upsert({
      where: { employeeId: person.employeeId },
      update: {
        username: person.username,
        employeeCode: person.employeeCode,
        fullName: person.fullName,
        phone: person.phone,
        specialization: person.specialization,
        salaryRankId: person.salaryRankId,
        status: "ACTIVE",
      },
      create: {
        employeeId: person.employeeId,
        username: person.username,
        employeeCode: person.employeeCode,
        fullName: person.fullName,
        phone: person.phone,
        specialization: person.specialization,
        salaryRankId: person.salaryRankId,
        status: "ACTIVE",
        createdAt: date(person.createdAt),
      },
    });
  }

  const certificates = [
    ["demo-cert-004", "demo-teacher-004", "IELTS", 8.0, null, "British Council"],
    ["demo-cert-005", "demo-teacher-005", "TOEIC", 965, null, "IIG Vietnam"],
    ["demo-cert-006", "demo-teacher-006", "IELTS", 8.5, null, "IDP Education"],
    ["demo-cert-007", "demo-teacher-007", "SAT", 1510, null, "College Board"],
    ["demo-cert-008", "demo-teacher-008", "TOEIC", 925, null, "IIG Vietnam"],
    ["demo-cert-009", "demo-teacher-009", "IELTS", 7.5, null, "British Council"],
  ];

  for (const [certificateId, employeeId, certificateType, score, scoreSW, issuer] of certificates) {
    await prisma.certificate.upsert({
      where: { certificateId },
      update: {
        employeeId,
        certificateType,
        score,
        scoreSW,
        issuer,
        status: "ACTIVE",
      },
      create: {
        certificateId,
        employeeId,
        certificateType,
        score,
        scoreSW,
        issueDate: date("2025-01-15"),
        expiryDate: date("2027-01-15"),
        issuer,
        status: "ACTIVE",
      },
    });
  }
}

async function seedCourseLessons(prisma) {
  const lessons = [
    ["demo-ielts-01", "course-ielts", "IELTS Overview & Diagnostic Test", 1],
    ["demo-ielts-02", "course-ielts", "Listening: Maps and Multiple Choice", 2],
    ["demo-ielts-03", "course-ielts", "Reading: Matching Headings", 3],
    ["demo-ielts-04", "course-ielts", "Writing Task 2: Opinion Essay", 4],
    ["demo-toeic-01", "course-toeic", "TOEIC Part 1–2 Fundamentals", 1],
    ["demo-toeic-02", "course-toeic", "TOEIC Part 3–4 Conversations", 2],
    ["demo-toeic-03", "course-toeic", "TOEIC Part 5 Grammar Review", 3],
    ["demo-toeic-04", "course-toeic", "TOEIC Part 7 Reading Strategies", 4],
    ["demo-sat-01", "course-sat", "SAT Algebra Foundations", 1],
    ["demo-sat-02", "course-sat", "SAT Advanced Math", 2],
    ["demo-sat-03", "course-sat", "SAT Reading & Writing", 3],
    ["demo-sat-04", "course-sat", "SAT Full Practice Review", 4],
    ["demo-toefl-01", "course-toefl", "TOEFL Academic Reading", 1],
    ["demo-toefl-02", "course-toefl", "TOEFL Integrated Listening", 2],
    ["demo-toefl-03", "course-toefl", "TOEFL Speaking Tasks", 3],
    ["demo-toefl-04", "course-toefl", "TOEFL Academic Writing", 4],
  ];

  for (const [lessonId, courseId, title, orderIndex] of lessons) {
    await prisma.lesson.upsert({
      where: { lessonId },
      update: { courseId, title, orderIndex, status: "ACTIVE" },
      create: {
        lessonId,
        courseId,
        title,
        description: "Nội dung học mẫu phục vụ kiểm thử nghiệp vụ lớp học.",
        contentType: "LESSON",
        orderIndex,
        status: "ACTIVE",
      },
    });
  }
}

async function seedClasses(prisma) {
  const classes = [
    ["demo-class-001", "course-ielts", "IELTS-65-EVE-01", "IELTS 6.5 Evening", "2026-11-02", "2027-02-12", "RUNNING", "admin"],
    ["demo-class-002", "course-toeic", "TOEIC-750-EVE-02", "TOEIC 750+ Evening", "2026-11-09", "2027-01-29", "RUNNING", "admin"],
    ["demo-class-003", "course-ielts", "IELTS-70-WKD-01", "IELTS 7.0 Weekend", "2026-11-14", "2027-03-06", "RUNNING", "admin"],
    ["demo-class-004", "course-sat", "SAT-1300-WKD-01", "SAT 1300+ Weekend", "2026-11-21", "2027-03-20", "READY", "admin"],
    ["demo-class-005", "course-toefl", "TOEFL-45-EVE-01", "TOEFL iBT 4.5+ Evening", "2026-11-23", "2027-02-26", "READY", "admin"],
    ["demo-class-006", "course-toeic", "TOEIC-900-INT-01", "TOEIC Intensive 900+", "2026-11-30", "2027-02-05", "READY", "admin"],
    ["demo-class-007", "course-ielts", "IELTS-55-FOUND-02", "IELTS Foundation 5.5", "2026-12-01", "2027-03-30", "DRAFT", "admin"],
    ["demo-class-008", "course-sat", "SAT-MATH-ADV-02", "SAT Math Advanced", "2026-12-05", "2027-03-27", "DRAFT", "admin"],
    ["demo-class-009", "course-toeic", "TOEIC-650-MOR-01", "TOEIC Foundation 650", "2026-10-05", "2026-12-23", "RUNNING", "admin"],
    ["demo-class-010", "course-ielts", "IELTS-WRITE-01", "IELTS Writing Focus", "2026-10-12", "2026-12-28", "RUNNING", "admin"],
  ];

  for (const [classId, courseId, classCode, name, startDate, endDate, status, createdByUsername] of classes) {
    await prisma.class.upsert({
      where: { classId },
      update: {
        courseId,
        classCode,
        name,
        startDate: date(startDate),
        endDate: date(endDate),
        status,
        createdByUsername,
      },
      create: {
        classId,
        courseId,
        classCode,
        name,
        startDate: date(startDate),
        endDate: date(endDate),
        status,
        createdByUsername,
      },
    });
  }

  const targets = [
    ["demo-ctr-001", "demo-class-001", "OVERALL_BAND", 6.5],
    ["demo-ctr-002a", "demo-class-002", "LR_TOTAL", 750],
    ["demo-ctr-002b", "demo-class-002", "SW_TOTAL", 240],
    ["demo-ctr-003", "demo-class-003", "OVERALL_BAND", 7.0],
    ["demo-ctr-004", "demo-class-004", "TOTAL", 1300],
    ["demo-ctr-005", "demo-class-005", "OVERALL_1_6", 4.5],
    ["demo-ctr-006a", "demo-class-006", "LR_TOTAL", 900],
    ["demo-ctr-006b", "demo-class-006", "SW_TOTAL", 300],
    ["demo-ctr-007", "demo-class-007", "OVERALL_BAND", 5.5],
    ["demo-ctr-008", "demo-class-008", "TOTAL", 1450],
    ["demo-ctr-009a", "demo-class-009", "LR_TOTAL", 650],
    ["demo-ctr-009b", "demo-class-009", "SW_TOTAL", 180],
    ["demo-ctr-010", "demo-class-010", "OVERALL_BAND", 6.5],
  ];

  for (const [classTargetRequirementId, classId, targetType, requiredTarget] of targets) {
    await prisma.classTargetRequirement.upsert({
      where: { classTargetRequirementId },
      update: { classId, targetType, requiredTarget },
      create: { classTargetRequirementId, classId, targetType, requiredTarget },
    });
  }
}

async function seedStudents(prisma) {
  const names = [
    "Nguyễn Minh Anh",
    "Trần Gia Bảo",
    "Lê Khánh Vy",
    "Phạm Quốc Huy",
    "Võ Ngọc Hà",
    "Đặng Tuấn Kiệt",
    "Bùi Thanh Trúc",
    "Hoàng Đức Anh",
    "Nguyễn Phương Linh",
    "Trương Minh Khang",
    "Lâm Bảo Ngọc",
    "Phan Hoàng Nam",
    "Đỗ Mỹ Duyên",
    "Huỳnh Quang Huy",
    "Ngô Anh Thư",
    "Dương Quốc Việt",
    "Mai Thảo Nhi",
    "Tạ Minh Quân",
    "Đinh Gia Hân",
    "Lý Tuấn Anh",
    "Cao Ngọc Mai",
    "Vũ Đức Long",
    "Hồ Khánh An",
    "Trịnh Thanh Hà",
  ];

  const statusByIndex = {
    7: "On Leave",
    16: "On Leave",
    23: "Graduated",
  };

  for (let index = 0; index < names.length; index += 1) {
    const number = index + 1;
    const studentId = `demo-student-${String(number).padStart(3, "0")}`;
    const studentCode = `HV-2026-${String(100 + number).padStart(4, "0")}`;
    const emailName = `hocvien${String(number).padStart(2, "0")}`;
    const status = statusByIndex[number] ?? "Active";

    await prisma.student.upsert({
      where: { studentId },
      update: {
        studentCode,
        fullName: names[index],
        phone: `0912${String(340000 + number).slice(-6)}`,
        email: `${emailName}@student.demo.local`,
        level: number % 3 === 0 ? "Intermediate" : number % 3 === 1 ? "Foundation" : "Upper-Intermediate",
        status,
      },
      create: {
        studentId,
        studentCode,
        fullName: names[index],
        phone: `0912${String(340000 + number).slice(-6)}`,
        email: `${emailName}@student.demo.local`,
        level: number % 3 === 0 ? "Intermediate" : number % 3 === 1 ? "Foundation" : "Upper-Intermediate",
        status,
      },
    });
  }

  const targetGroups = [
    { range: [1, 6], courseId: "course-ielts", targetType: "OVERALL_BAND", values: [6.5, 7.0, 7.5, 6.5, 7.0, 7.5] },
    { range: [7, 12], courseId: "course-toeic", targetType: "LR_TOTAL", values: [700, 750, 800, 850, 900, 950] },
    { range: [7, 12], courseId: "course-toeic", targetType: "SW_TOTAL", values: [200, 240, 260, 280, 300, 320] },
    { range: [13, 18], courseId: "course-sat", targetType: "TOTAL", values: [1250, 1300, 1350, 1400, 1450, 1500] },
    { range: [19, 24], courseId: "course-toefl", targetType: "OVERALL_1_6", values: [4.0, 4.5, 5.0, 5.0, 5.5, 6.0] },
  ];

  for (const group of targetGroups) {
    const [start, end] = group.range;
    for (let number = start; number <= end; number += 1) {
      const studentId = `demo-student-${String(number).padStart(3, "0")}`;
      const studentTargetId = `demo-target-${number}-${group.targetType.toLowerCase()}`;
      const targetValue = group.values[number - start];

      await prisma.studentTarget.upsert({
        where: { studentTargetId },
        update: {
          studentId,
          courseId: group.courseId,
          targetType: group.targetType,
          targetValue,
        },
        create: {
          studentTargetId,
          studentId,
          courseId: group.courseId,
          targetType: group.targetType,
          targetValue,
        },
      });
    }
  }

  const memberships = [
    ["demo-class-001", [1, 2, 3, 4]],
    ["demo-class-003", [3, 5, 6]],
    ["demo-class-002", [8, 9, 10]],
    ["demo-class-009", [7, 11, 12]],
    ["demo-class-004", [13, 14, 15]],
    ["demo-class-005", [20, 21, 22]],
    ["demo-class-010", [1, 4, 5]],
  ];

  let membershipIndex = 1;
  for (const [classId, studentNumbers] of memberships) {
    for (const number of studentNumbers) {
      const studentId = `demo-student-${String(number).padStart(3, "0")}`;
      const classStudentId = `demo-membership-${String(membershipIndex).padStart(3, "0")}`;
      membershipIndex += 1;
      await prisma.classStudent.upsert({
        where: { classStudentId },
        update: { classId, studentId, status: "ACTIVE", inactiveReason: null, inactivatedAt: null },
        create: { classStudentId, classId, studentId, status: "ACTIVE" },
      });
    }
  }
}

async function seedAvailabilities(prisma) {
  const rows = [
    ["demo-avail-t004-mon", "demo-teacher-004", "mon", "18:00", "21:30", "evening", "Cơ sở chính", "TC"],
    ["demo-avail-t004-wed", "demo-teacher-004", "wed", "18:00", "21:30", "evening", "Cơ sở chính", "TC"],
    ["demo-avail-t004-sat", "demo-teacher-004", "sat", "08:00", "12:00", "morning", "Cơ sở chính", "TC"],
    ["demo-avail-t005-tue", "demo-teacher-005", "tue", "18:00", "21:30", "evening", "Cơ sở Quận 1", "TC"],
    ["demo-avail-t005-thu", "demo-teacher-005", "thu", "18:00", "21:30", "evening", "Cơ sở Quận 1", "TC"],
    ["demo-avail-t005-sun", "demo-teacher-005", "sun", "08:00", "12:00", "morning", "Cơ sở Quận 1", "TC"],
    ["demo-avail-t006-mon", "demo-teacher-006", "mon", "18:00", "21:30", "evening", "Cơ sở Quận 3", "TC"],
    ["demo-avail-t006-fri", "demo-teacher-006", "fri", "18:00", "21:30", "evening", "Cơ sở Quận 3", "TC"],
    ["demo-avail-t007-wed", "demo-teacher-007", "wed", "18:00", "21:30", "evening", "Cơ sở chính", "TC"],
    ["demo-avail-t007-sat", "demo-teacher-007", "sat", "08:00", "12:30", "morning", "Cơ sở chính", "TC"],
    ["demo-avail-t008-tue", "demo-teacher-008", "tue", "18:00", "21:30", "evening", "Cơ sở Quận 3", "TC"],
    ["demo-avail-t008-fri", "demo-teacher-008", "fri", "18:00", "21:30", "evening", "Cơ sở Quận 3", "TC"],
    ["demo-avail-t009-thu", "demo-teacher-009", "thu", "18:00", "21:30", "evening", "Cơ sở chính", "TC"],
    ["demo-avail-t009-sun", "demo-teacher-009", "sun", "08:00", "12:30", "morning", "Cơ sở chính", "TC"],

    ["demo-avail-cs004-mon", "demo-cs-004", "mon", "17:00", "21:30", "evening", "Cơ sở chính", "CM"],
    ["demo-avail-cs004-wed", "demo-cs-004", "wed", "17:00", "21:30", "evening", "Cơ sở chính", "CM"],
    ["demo-avail-cs005-tue", "demo-cs-005", "tue", "17:00", "21:30", "evening", "Cơ sở Quận 1", "CM"],
    ["demo-avail-cs005-thu", "demo-cs-005", "thu", "17:00", "21:30", "evening", "Cơ sở Quận 1", "CM"],
    ["demo-avail-cs006-fri", "demo-cs-006", "fri", "17:00", "21:30", "evening", "Cơ sở Quận 3", "CM"],
    ["demo-avail-cs006-sat", "demo-cs-006", "sat", "08:00", "17:00", "day", "Cơ sở Quận 3", "CM"],

    ["demo-avail-sale004-mon", "demo-sale-004", "mon", "08:00", "17:30", "day", "Cơ sở chính", "CM"],
    ["demo-avail-sale004-tue", "demo-sale-004", "tue", "08:00", "17:30", "day", "Cơ sở chính", "CM"],
    ["demo-avail-sale005-wed", "demo-sale-005", "wed", "08:00", "17:30", "day", "Cơ sở Quận 1", "CM"],
    ["demo-avail-sale005-thu", "demo-sale-005", "thu", "08:00", "17:30", "day", "Cơ sở Quận 1", "CM"],
    ["demo-avail-sale006-fri", "demo-sale-006", "fri", "08:00", "17:30", "day", "Cơ sở Quận 3", "CM"],
    ["demo-avail-sale006-sat", "demo-sale-006", "sat", "08:00", "17:00", "day", "Cơ sở Quận 3", "CM"],
  ];

  for (const [availabilityId, employeeId, dayOfWeek, startTime, endTime, shift, campus, reviewerRole] of rows) {
    const reviewedById = reviewerRole === "TC" ? "emp-tc-001" : "emp-cm-001";
    await prisma.availability.upsert({
      where: { availabilityId },
      update: {
        employeeId,
        availableDate: null,
        dayOfWeek,
        startTime: time(startTime),
        endTime: time(endTime),
        shift,
        availabilityType: "RECURRING",
        campus,
        note: "Lịch rảnh định kỳ phục vụ dữ liệu demo nghiệp vụ.",
        status: "APPROVED",
        reviewedAt: date("2026-11-15"),
        reviewedById,
      },
      create: {
        availabilityId,
        employeeId,
        availableDate: null,
        dayOfWeek,
        startTime: time(startTime),
        endTime: time(endTime),
        shift,
        availabilityType: "RECURRING",
        campus,
        note: "Lịch rảnh định kỳ phục vụ dữ liệu demo nghiệp vụ.",
        status: "APPROVED",
        reviewedAt: date("2026-11-15"),
        reviewedById,
      },
    });
  }
}

async function seedClassSessionsAndSchedules(prisma) {
  const sessions = [
    ["demo-session-001", "demo-class-001", "2026-11-23", "18:30", "20:30"],
    ["demo-session-002", "demo-class-002", "2026-11-24", "18:30", "20:30"],
    ["demo-session-003", "demo-class-003", "2026-11-25", "18:30", "20:30"],
    ["demo-session-004", "demo-class-005", "2026-11-27", "18:30", "20:30"],
    ["demo-session-005", "demo-class-004", "2026-11-28", "09:00", "11:00"],
    ["demo-session-006", "demo-class-009", "2026-11-27", "18:30", "20:30"],
    ["demo-session-007", "demo-class-010", "2026-11-26", "18:30", "20:30"],
    ["demo-session-008", "demo-class-001", "2026-11-25", "18:30", "20:30"],
    ["demo-session-009", "demo-class-002", "2026-11-26", "18:30", "20:30"],
    ["demo-session-010", "demo-class-003", "2026-11-28", "09:00", "11:00"],
    ["demo-session-011", "demo-class-005", "2026-11-30", "18:30", "20:30"],
    ["demo-session-012", "demo-class-004", "2026-12-02", "18:30", "20:30"],
  ];

  for (const [classSessionId, classId, sessionDate, startTime, endTime] of sessions) {
    await prisma.classSession.upsert({
      where: {
        classId_date_startTime_endTime: {
          classId,
          date: date(sessionDate),
          startTime: time(startTime),
          endTime: time(endTime),
        },
      },
      update: { status: "PLANNED" },
      create: {
        classSessionId,
        classId,
        date: date(sessionDate),
        startTime: time(startTime),
        endTime: time(endTime),
        status: "PLANNED",
      },
    });
  }

  const teaching = [
    ["demo-teaching-001", "demo-teacher-004", "demo-class-001", "2026-11-23", "18:30", "20:30", "Cơ sở chính"],
    ["demo-teaching-002", "demo-teacher-005", "demo-class-002", "2026-11-24", "18:30", "20:30", "Cơ sở Quận 1"],
    ["demo-teaching-003", "demo-teacher-007", "demo-class-003", "2026-11-25", "18:30", "20:30", "Cơ sở chính"],
    ["demo-teaching-004", "demo-teacher-008", "demo-class-009", "2026-11-27", "18:30", "20:30", "Cơ sở Quận 3"],
    ["demo-teaching-005", "demo-teacher-009", "demo-class-010", "2026-11-26", "18:30", "20:30", "Cơ sở chính"],
  ];

  for (const [teachingScheduleId, teacherId, classId, scheduleDate, startTime, endTime, campus] of teaching) {
    await prisma.teachingSchedule.upsert({
      where: { teachingScheduleId },
      update: {
        teacherId,
        classId,
        date: date(scheduleDate),
        startTime: time(startTime),
        endTime: time(endTime),
        campus,
        status: "ASSIGNED",
        assignedById: "emp-tc-001",
      },
      create: {
        teachingScheduleId,
        teacherId,
        classId,
        date: date(scheduleDate),
        startTime: time(startTime),
        endTime: time(endTime),
        campus,
        status: "ASSIGNED",
        assignedById: "emp-tc-001",
      },
    });
  }

  const staffSchedules = [
    ["demo-staff-001", "demo-cs-004", "demo-class-001", "center-01", "2026-11-23", "18:30", "20:30", "CLASS_SUPPORT"],
    ["demo-staff-002", "demo-cs-005", "demo-class-002", "center-02", "2026-11-24", "18:30", "20:30", "CLASS_SUPPORT"],
    ["demo-staff-003", "demo-cs-004", "demo-class-003", "center-01", "2026-11-25", "18:30", "20:30", "CLASS_SUPPORT"],
    ["demo-staff-004", "demo-sale-004", null, "center-01", "2026-11-23", "08:30", "12:00", "SALES_SHIFT"],
    ["demo-staff-005", "demo-sale-005", null, "center-02", "2026-11-25", "13:00", "17:00", "LEAD_FOLLOWUP"],
    ["demo-staff-006", "demo-sale-006", null, "center-03", "2026-11-27", "08:30", "12:00", "CONSULTATION"],
  ];

  for (const [staffScheduleId, employeeId, classId, workplaceId, scheduleDate, startTime, endTime, workType] of staffSchedules) {
    await prisma.staffSchedule.upsert({
      where: { staffScheduleId },
      update: {
        employeeId,
        classId,
        workplaceId,
        date: date(scheduleDate),
        startTime: time(startTime),
        endTime: time(endTime),
        workType,
        status: "ASSIGNED",
        assignedById: "emp-cm-001",
        assignmentSource: "STANDARD",
      },
      create: {
        staffScheduleId,
        employeeId,
        classId,
        workplaceId,
        date: date(scheduleDate),
        startTime: time(startTime),
        endTime: time(endTime),
        workType,
        status: "ASSIGNED",
        assignedById: "emp-cm-001",
        assignmentSource: "STANDARD",
      },
    });
  }

  const scopes = [
    ["demo-scope-001", "demo-cs-004", "demo-class-001"],
    ["demo-scope-002", "demo-cs-005", "demo-class-002"],
    ["demo-scope-003", "demo-cs-004", "demo-class-003"],
    ["demo-scope-004", "demo-cs-006", "demo-class-009"],
    ["demo-scope-005", "demo-cs-006", "demo-class-010"],
  ];

  for (const [classAccessScopeId, employeeId, classId] of scopes) {
    await prisma.classAccessScope.upsert({
      where: { classAccessScopeId },
      update: { employeeId, classId, status: "ACTIVE", source: "ASSIGNED_SCOPE" },
      create: { classAccessScopeId, employeeId, classId, status: "ACTIVE", source: "ASSIGNED_SCOPE" },
    });
  }
}

async function seedAcademicActivities(prisma) {
  const assignments = [
    ["demo-assignment-001", "demo-teacher-004", "demo-class-001", "Writing Task 2 - Education", "2026-11-29T23:59:00Z"],
    ["demo-assignment-002", "demo-teacher-005", "demo-class-002", "TOEIC Part 5 Practice Set", "2026-11-30T23:59:00Z"],
    ["demo-assignment-003", "demo-teacher-007", "demo-class-003", "Reading Practice - Matching Headings", "2026-12-02T23:59:00Z"],
    ["demo-assignment-004", "demo-teacher-009", "demo-class-010", "Writing Task 1 - Line Graph", "2026-12-03T23:59:00Z"],
  ];

  for (const [assignmentId, teacherId, classId, title, deadline] of assignments) {
    await prisma.assignment.upsert({
      where: { assignmentId },
      update: { teacherId, classId, title, deadline: new Date(deadline), status: "OPEN" },
      create: {
        assignmentId,
        teacherId,
        classId,
        title,
        description: "Bài tập thực hành theo tiến độ lớp.",
        deadline: new Date(deadline),
        status: "OPEN",
      },
    });
  }

  const exams = [
    ["demo-exam-001", "demo-teacher-004", "demo-class-001", "IELTS Progress Test 1", 90, "2026-12-05T09:00:00Z"],
    ["demo-exam-002", "demo-teacher-005", "demo-class-002", "TOEIC Mini Test 1", 75, "2026-12-06T09:00:00Z"],
    ["demo-exam-003", "demo-teacher-007", "demo-class-003", "IELTS Weekend Mock Test", 120, "2026-12-12T08:00:00Z"],
  ];

  for (const [examId, teacherId, classId, title, duration, examDate] of exams) {
    await prisma.exam.upsert({
      where: { examId },
      update: { teacherId, classId, title, duration, examDate: new Date(examDate), status: "SCHEDULED" },
      create: {
        examId,
        teacherId,
        classId,
        title,
        description: "Bài kiểm tra tiến độ dành cho dữ liệu demo.",
        duration,
        examDate: new Date(examDate),
        status: "SCHEDULED",
      },
    });
  }
}

async function seedPayroll(prisma) {
  const teacherPayroll = [
    ["demo-pay-teacher-004", "demo-teacher-004", 8, 16, 230000],
    ["demo-pay-teacher-005", "demo-teacher-005", 10, 20, 230000],
    ["demo-pay-teacher-006", "demo-teacher-006", 12, 24, 300000],
    ["demo-pay-teacher-007", "demo-teacher-007", 8, 16, 230000],
    ["demo-pay-teacher-008", "demo-teacher-008", 6, 12, 180000],
    ["demo-pay-teacher-009", "demo-teacher-009", 9, 18, 230000],
  ];

  for (const [employeeSalaryId, employeeId, totalSessions, totalHours, rate] of teacherPayroll) {
    const amount = totalHours * rate;
    await prisma.employeeSalary.upsert({
      where: { employeeSalaryId },
      update: { employeeId, period: "2026-10", totalSessions, totalHours, rate, amount, status: "APPROVED" },
      create: { employeeSalaryId, employeeId, period: "2026-10", totalSessions, totalHours, rate, amount, status: "APPROVED" },
    });
    await prisma.employeeSalaryDetail.upsert({
      where: { employeeSalaryId },
      update: { totalSessions, salaryPerHours: rate, totalHours, totalSalary: amount },
      create: { employeeSalaryId, totalSessions, salaryPerHours: rate, totalHours, totalSalary: amount },
    });
  }

  const staffPayroll = [
    ["demo-pay-cs004", "demo-cs-004", 20, 82, 55000],
    ["demo-pay-cs005", "demo-cs-005", 22, 90, 70000],
    ["demo-pay-cs006", "demo-cs-006", 18, 74, 55000],
    ["demo-pay-sale004", "demo-sale-004", 21, 86, 55000],
    ["demo-pay-sale005", "demo-sale-005", 22, 90, 70000],
    ["demo-pay-sale006", "demo-sale-006", 19, 78, 55000],
  ];

  for (const [staffSalaryId, employeeId, totalShifts, totalHours, rate] of staffPayroll) {
    const amount = totalHours * rate;
    await prisma.staffSalary.upsert({
      where: { staffSalaryId },
      update: {
        employeeId,
        period: "2026-10",
        totalShifts,
        totalHours,
        rate,
        amount,
        status: "APPROVED",
        managedById: "emp-cm-001",
      },
      create: {
        staffSalaryId,
        employeeId,
        period: "2026-10",
        totalShifts,
        totalHours,
        rate,
        amount,
        status: "APPROVED",
        managedById: "emp-cm-001",
      },
    });
  }
}

async function seedRealisticDemoData(prisma, passwordHash) {
  await cleanupLegacyAutoSeed(prisma);
  await seedFunctionsAndPermissions(prisma);
  await seedSalaryRanks(prisma);
  await seedEmployees(prisma, passwordHash);
  await seedCourseLessons(prisma);
  await seedClasses(prisma);
  await seedStudents(prisma);
  await seedAvailabilities(prisma);
  await seedClassSessionsAndSchedules(prisma);
  await seedAcademicActivities(prisma);
  await seedPayroll(prisma);

  console.log("Realistic demo dataset loaded.");
  console.log("Extra demo accounts use password: 123456");
}

module.exports = {
  cleanupLegacyAutoSeed,
  seedRealisticDemoData,
};
